#!/usr/bin/env python3
"""Find the phone's wireless-debugging *connect* port by TCP-scanning 30000-50000.

`adb mdns services` doesn't work across VPNs like Tailscale, and the port changes every
time wireless debugging restarts (every reboot). Prints each open port, one per line.

Usage: scripts/find-adb-port.py <phone-ip>
"""
import asyncio
import sys


async def probe(host: str, port: int, sem: asyncio.Semaphore) -> None:
    async with sem:
        try:
            _, writer = await asyncio.wait_for(asyncio.open_connection(host, port), 1.5)
            writer.close()
            print(port, flush=True)
        except Exception:
            pass


async def main(host: str) -> None:
    sem = asyncio.Semaphore(800)
    await asyncio.gather(*(probe(host, p, sem) for p in range(30000, 50001)))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    asyncio.run(main(sys.argv[1]))
