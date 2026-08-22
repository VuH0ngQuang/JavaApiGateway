// Minimal fixed-response HTTP server, for benchmarking the gateway against
// a uniform pool of identical backends.
//
// Usage: dummy_backend <port> [service_name] [body_bytes]
//
// Replaces the old Python (http.server) version: Go's net/http has no
// small default listen backlog (Python's socketserver.TCPServer defaults
// to 5, which resets connections under high concurrency regardless of
// CPU headroom) and no GIL serializing request handling within a process.
//
// The payload is random alphanumeric data, generated once at startup: doing
// it per request would make the RNG the bottleneck instead of the gateway.
package main

import (
	"encoding/json"
	"fmt"
	"log"
	"math/rand"
	"net"
	"net/http"
	"os"
	"strconv"
)

const letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

func randomString(n int) string {
	b := make([]byte, n)
	for i := range b {
		b[i] = letters[rand.Intn(len(letters))]
	}
	return string(b)
}

func buildBody(service string, port int, targetBytes int) []byte {
	envelope := map[string]interface{}{"service": service, "port": port, "payload": ""}
	overhead, _ := json.Marshal(envelope)
	pad := targetBytes - len(overhead)
	if pad < 0 {
		pad = 0
	}
	envelope["payload"] = randomString(pad)
	body, _ := json.Marshal(envelope)
	return body
}

func main() {
	port := 9001
	service := "dummy"
	bodyBytes := 64 * 1024

	if len(os.Args) > 1 {
		port, _ = strconv.Atoi(os.Args[1])
	}
	if len(os.Args) > 2 {
		service = os.Args[2]
	}
	if len(os.Args) > 3 {
		bodyBytes, _ = strconv.Atoi(os.Args[3])
	}

	body := buildBody(service, port, bodyBytes)

	handler := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Content-Length", strconv.Itoa(len(body)))
		w.WriteHeader(http.StatusOK)
		w.Write(body)
	}

	addr := fmt.Sprintf("0.0.0.0:%d", port)
	// Explicit listener + large backlog (Go's default is already the OS max
	// via net.ListenConfig, but spell it out since the small Python backlog
	// was the whole reason for this rewrite).
	lc := net.ListenConfig{}
	ln, err := lc.Listen(nil, "tcp", addr)
	if err != nil {
		log.Fatalf("failed to listen on %s: %v", addr, err)
	}

	fmt.Printf("dummy backend '%s' listening on :%d (%d byte body)\n", service, port, len(body))
	server := &http.Server{Handler: http.HandlerFunc(handler)}
	log.Fatal(server.Serve(ln))
}
