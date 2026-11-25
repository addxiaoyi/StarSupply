import json
import sqlite3
import sys
import time
from pathlib import Path


def main():
    database, stop_file, hold_ms, pause_ms = sys.argv[1:]
    hold_seconds = int(hold_ms) / 1000
    pause_seconds = int(pause_ms) / 1000
    connection = sqlite3.connect(database, timeout=0.05, isolation_level=None)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute(
        "CREATE TABLE IF NOT EXISTS starsupply_acceptance_retry_probe "
        "(id INTEGER PRIMARY KEY, writes INTEGER NOT NULL)"
    )
    connection.execute(
        "INSERT OR IGNORE INTO starsupply_acceptance_retry_probe (id, writes) VALUES (1, 0)"
    )
    print(json.dumps({"event": "writer_ready", "journalMode": "WAL"}), flush=True)

    writes = 0
    failures = 0
    stop_path = Path(stop_file)
    try:
        while not stop_path.exists():
            try:
                connection.execute("BEGIN IMMEDIATE")
                connection.execute(
                    "UPDATE starsupply_acceptance_retry_probe "
                    "SET writes = writes + 1 WHERE id = 1"
                )
                time.sleep(hold_seconds)
                connection.execute("COMMIT")
                writes += 1
            except sqlite3.OperationalError:
                failures += 1
                if connection.in_transaction:
                    connection.execute("ROLLBACK")
            time.sleep(pause_seconds)
    finally:
        connection.close()
        print(
            json.dumps(
                {
                    "event": "writer_stopped",
                    "committedWrites": writes,
                    "busyOrLockFailures": failures,
                }
            ),
            flush=True,
        )


if __name__ == "__main__":
    main()
