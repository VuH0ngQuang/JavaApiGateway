#!/usr/bin/env bash
# Stop everything a benchmark run leaves behind: the gateway and every dummy
# backend. Run this after testing so stale JVMs/Python servers don't hold ports
# or skew the next run.
#
# Usage: ./benchmarks/stop-all.sh
#
# Kills by listening port rather than by process name. `pkill -f dummy_backend`
# matches its own shell (the pattern appears in the shell's command line) and
# kills the script itself.

set -u

PORTS=(1221 8081 8082 9081 9082 9083)

for PORT in "${PORTS[@]}"; do
    PID=$(ss -ltnp 2>/dev/null | grep ":${PORT} " | grep -o 'pid=[0-9]*' | cut -d= -f2 | head -1)
    if [ -n "$PID" ]; then
        CMD=$(ps -p "$PID" -o comm= 2>/dev/null)
        kill "$PID" 2>/dev/null && echo "killed $CMD (pid $PID) on :$PORT"
    else
        echo ":$PORT — nothing listening"
    fi
done

sleep 2

echo
echo "still listening:"
ss -ltn 2>/dev/null | grep -E ':(1221|8081|8082|9081|9082|9083) ' || echo "  none — all clear"
