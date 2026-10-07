// вывод в консоли clrscr
package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"
)

const (
	DefaultIPv4Group = "239.255.42.99"
	DefaultIPv6Group = "ff02::4299"
	DefaultPort      = "8888"

	heartbeatInterval = 2 * time.Second
	nodeTimeout       = 6 * time.Second
)

func generateRandomID() string {
	bytes := make([]byte, 4)
	if _, err := rand.Read(bytes); err != nil {
		return fmt.Sprintf("%x", time.Now().UnixNano())[:8]
	}
	return hex.EncodeToString(bytes)
}

type PeerTracker struct {
	mu    sync.Mutex
	peers map[string]time.Time
}

func NewPeerTracker() *PeerTracker {
	return &PeerTracker{
		peers: make(map[string]time.Time),
	}
}

func (pt *PeerTracker) Update(peerKey string) bool {
	pt.mu.Lock()
	defer pt.mu.Unlock()

	_, exists := pt.peers[peerKey]
	pt.peers[peerKey] = time.Now()
	return !exists
}

func (pt *PeerTracker) Remove(peerKey string) bool {
	pt.mu.Lock()
	defer pt.mu.Unlock()

	if _, exists := pt.peers[peerKey]; exists {
		delete(pt.peers, peerKey)
		return true
	}
	return false
}

func (pt *PeerTracker) Cleanup() bool {
	pt.mu.Lock()
	defer pt.mu.Unlock()

	changed := false
	now := time.Now()
	for peerKey, lastSeen := range pt.peers {
		if now.Sub(lastSeen) > nodeTimeout {
			delete(pt.peers, peerKey)
			changed = true
		}
	}
	return changed
}

func (pt *PeerTracker) PrintList() {
	pt.mu.Lock()
	defer pt.mu.Unlock()

	fmt.Println("\n=== Список живых копий в сети ===")
	if len(pt.peers) == 0 {
		fmt.Println("(нет других активных копий)")
	} else {
		for peerKey := range pt.peers {
			fmt.Printf(" - %s\n", peerKey)
		}
	}
	fmt.Println("=================================")
}

func getMulticastInterface(network string) (*net.Interface, net.IP, error) {
	ifaces, err := net.Interfaces()
	if err != nil {
		return nil, nil, err
	}

	for _, iface := range ifaces {
		if (iface.Flags&net.FlagUp) == 0 || (iface.Flags&net.FlagMulticast) == 0 || (iface.Flags&net.FlagLoopback) != 0 {
			continue
		}

		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}

		for _, addr := range addrs {
			ipNet, ok := addr.(*net.IPNet)
			if !ok {
				continue
			}

			if network == "udp6" && ipNet.IP.To4() == nil && ipNet.IP.IsLinkLocalUnicast() {
				return &iface, ipNet.IP, nil
			} else if network == "udp4" && ipNet.IP.To4() != nil {
				return &iface, ipNet.IP, nil
			}
		}
	}
	return nil, nil, fmt.Errorf("не найден интерфейс с активным %s адресом", network)
}

func main() {
	nodeID := generateRandomID()

	groupIPStr := DefaultIPv6Group
	if len(os.Args) > 1 {
		groupIPStr = os.Args[1]
	}

	groupIP := net.ParseIP(groupIPStr)
	if groupIP == nil || !groupIP.IsMulticast() {
		fmt.Printf("Ошибка: '%s' не является валидным multicast IP-адресом\n", groupIPStr)
		return
	}

	network := "udp4"
	if groupIP.To4() == nil {
		network = "udp6"
	}

	iface, localIP, err := getMulticastInterface(network)
	if err != nil {
		fmt.Printf("Ошибка получения интерфейса: %v\n", err)
		return
	}

	addr, err := net.ResolveUDPAddr(network, net.JoinHostPort(groupIPStr, DefaultPort))
	if err != nil {
		fmt.Printf("Ошибка разрешения адреса: %v\n", err)
		return
	}

	conn, err := net.ListenMulticastUDP(network, iface, addr)
	if err != nil {
		fmt.Printf("Ошибка подключения к multicast: %v\n", err)
		return
	}
	defer conn.Close()

	sendAddr := addr
	var bindLocalAddr *net.UDPAddr

	if network == "udp6" {
		if addr.IP.IsLinkLocalMulticast() {
			sendAddr = &net.UDPAddr{
				IP:   addr.IP,
				Port: addr.Port,
				Zone: iface.Name,
			}
		}
	} else {
		bindLocalAddr = &net.UDPAddr{
			IP:   localIP,
			Port: 0,
		}
	}

	sendConn, err := net.DialUDP(network, bindLocalAddr, sendAddr)
	if err != nil {
		fmt.Printf("Ошибка создания отправляющего сокета: %v\n", err)
		return
	}
	defer sendConn.Close()

	localPort := sendConn.LocalAddr().(*net.UDPAddr).Port

	tracker := NewPeerTracker()
	fmt.Printf("Запущено обнаружение копий [Node ID: %s] на адресе [%s]:%s (%s) через %s (Local Port: %d)\n",
		nodeID, groupIPStr, DefaultPort, network, iface.Name, localPort)
	fmt.Println("Для выхода нажмите Ctrl+C")

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)

	stopChan := make(chan struct{})

	go func() {
		ticker := time.NewTicker(heartbeatInterval)
		defer ticker.Stop()

		msg := fmt.Sprintf("HELLO %d %s", localPort, nodeID)
		sendConn.Write([]byte(msg))

		for {
			select {
			case <-ticker.C:
				sendConn.Write([]byte(msg))
			case <-stopChan:
				return
			}
		}
	}()

	go func() {
		ticker := time.NewTicker(1 * time.Second)
		defer ticker.Stop()

		for {
			select {
			case <-ticker.C:
				if tracker.Cleanup() {
					tracker.PrintList()
				}
			case <-stopChan:
				return
			}
		}
	}()

	go func() {
		buf := make([]byte, 1024)
		for {
			n, srcAddr, err := conn.ReadFromUDP(buf)
			if err != nil {
				return
			}

			if srcAddr.Port == localPort {
				continue
			}

			payload := string(buf[:n])

			ipAddr, ok := netip.AddrFromSlice(srcAddr.IP)
			if !ok {
				continue
			}
			peerIP := ipAddr.Unmap().WithZone("").String()

			var senderPort int
			var action, remoteNodeID string
			fmt.Sscanf(payload, "%s %d %s", &action, &senderPort, &remoteNodeID)

			peerKey := fmt.Sprintf("%s (node-%s)", peerIP, remoteNodeID)

			if action == "HELLO" {
				if tracker.Update(peerKey) {
					tracker.PrintList()
				}
			} else if action == "BYE" {
				if tracker.Remove(peerKey) {
					tracker.PrintList()
				}
			}
		}
	}()

	<-sigChan
	fmt.Println("\nЗавершение работы, отправка 'BYE'...")

	byeMsg := fmt.Sprintf("BYE %d %s", localPort, nodeID)
	sendConn.Write([]byte(byeMsg))

	close(stopChan)
	time.Sleep(200 * time.Millisecond)
}
