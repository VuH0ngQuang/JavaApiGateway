// Minimal UDP DNS server for testing DnsServiceDiscovery: answers A-record
// queries for any hostname with whatever IPs are listed in the given file
// (one per line, re-read on every query). Updating that file between polls
// lets a test simulate DNS records changing -- backends appearing or
// disappearing -- without restarting the server, to exercise
// DnsServiceDiscovery's add/remove reconciliation logic end-to-end.
//
// Usage: fake_dns <port> <ips_file>
//
// Point DnsServiceDiscovery's resolver at 127.0.0.1:<port> for a test
// (temporarily, via a nameServerProvider override) rather than the system
// resolver, so this never touches /etc/hosts or /etc/resolv.conf.
package main

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
)

func readIPs(path string) []net.IP {
	f, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer f.Close()

	var ips []net.IP
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		if ip := net.ParseIP(line).To4(); ip != nil {
			ips = append(ips, ip)
		}
	}
	return ips
}

// skipName advances past a DNS name (length-prefixed labels ending in a
// zero-length terminator) starting at offset, returning the offset just
// past it. Doesn't follow compression pointers -- a query's own question
// name is never compressed.
func skipName(msg []byte, offset int) int {
	for {
		length := int(msg[offset])
		if length == 0 {
			return offset + 1
		}
		offset += 1 + length
	}
}

func buildResponse(query []byte, ips []net.IP) []byte {
	id := query[0:2]
	// Bound the question section explicitly (name + QTYPE + QCLASS) instead
	// of blindly copying everything after the header -- a real query often
	// carries an EDNS0 OPT record afterward, and copying that while claiming
	// ARCOUNT=0 produces a malformed response.
	qEnd := skipName(query, 12) + 4
	question := query[12:qEnd]

	resp := make([]byte, 0, 12+len(question)+16*len(ips))
	resp = append(resp, id...)
	resp = append(resp, 0x81, 0x80) // standard response, no error
	resp = append(resp, 0, 1)       // QDCOUNT = 1

	ancount := make([]byte, 2)
	binary.BigEndian.PutUint16(ancount, uint16(len(ips)))
	resp = append(resp, ancount...)
	resp = append(resp, 0, 0) // NSCOUNT
	resp = append(resp, 0, 0) // ARCOUNT
	resp = append(resp, question...)

	for _, ip := range ips {
		resp = append(resp, 0xc0, 0x0c) // name pointer -> the question name at offset 12
		resp = append(resp, 0, 1)       // TYPE = A
		resp = append(resp, 0, 1)       // CLASS = IN
		resp = append(resp, 0, 0, 0, 5) // TTL = 5s, short so a test's next poll never sees a stale cached answer
		resp = append(resp, 0, 4)       // RDLENGTH = 4 (one IPv4 address)
		resp = append(resp, ip...)
	}
	return resp
}

func main() {
	if len(os.Args) < 3 {
		fmt.Println("usage: fake_dns <port> <ips_file>")
		os.Exit(1)
	}
	port, err := strconv.Atoi(os.Args[1])
	if err != nil {
		fmt.Println("bad port:", err)
		os.Exit(1)
	}
	ipsFile := os.Args[2]

	conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: port})
	if err != nil {
		fmt.Println("listen failed:", err)
		os.Exit(1)
	}
	defer conn.Close()
	fmt.Printf("fake DNS server listening on 127.0.0.1:%d, serving IPs from %s\n", port, ipsFile)

	buf := make([]byte, 512)
	for {
		n, clientAddr, err := conn.ReadFromUDP(buf)
		if err != nil {
			continue
		}
		ips := readIPs(ipsFile)
		resp := buildResponse(buf[:n], ips)
		conn.WriteToUDP(resp, clientAddr)
		fmt.Printf("answered query from %s with %v\n", clientAddr, ips)
	}
}
