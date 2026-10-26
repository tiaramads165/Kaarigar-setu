#!/usr/bin/env bash
# One-time codes in development are written to the server log, not sent anywhere. This shows them.
#   scripts/otp.sh            the latest codes (newest last)
#   scripts/otp.sh -f         follow: print each new code the moment it is issued (Ctrl-C to stop)
# The log is data/logs/dev.log under this repository, wherever the repository lives (override with KS_LOG=/path/to/log).
# If the server was started by "./scripts/dev.sh" in a terminal, the same lines also appear in that terminal.
cd "$(dirname "$0")/.."
LOG="${KS_LOG:-$PWD/data/logs/dev.log}"
if [ ! -f "$LOG" ]; then
  echo "No server log at $LOG. Start the app with ./scripts/dev.sh (or point KS_LOG at its log file)." >&2
  exit 1
fi
PATTERN='CONSOLE OTP|\[SMS\]'
if [ "${1:-}" = "-f" ]; then
  echo "Watching $LOG for one-time codes (Ctrl-C to stop)..."
  exec tail -n 0 -F "$LOG" | grep -a --line-buffered -E "$PATTERN" | sed -u 's/^\[INFO\] INFO: //'
fi
echo "Latest codes from $LOG (newest last):"
grep -a -E "$PATTERN" "$LOG" | tail -n "${N:-8}" | sed 's/^\[INFO\] INFO: //'
