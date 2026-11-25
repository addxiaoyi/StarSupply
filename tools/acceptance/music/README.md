# Music player acceptance

This runner validates the Music plugin against an already running, isolated Paper or Folia stage. It does not start or stop a server. It writes two small MIDI fixtures under that stage's `plugins/KaleidoscopeMusicPlugin/songs/`, uses local RCON to grant an offline test player operator permission, and records the inventory item, keyboard menu, and note-block sound packets observed by Mineflayer.

Prepare the fixtures before startup or let the runner create them when it starts:

```powershell
node .\tools\acceptance\music\run.cjs --prepare C:\path\to\isolated-stage
```

After the isolated stage is running with CraftEngine, its Music content resources, and `KaleidoscopeMusicPlugin` enabled, run:

```powershell
node .\tools\acceptance\music\run.cjs `
  C:\path\to\isolated-stage `
  C:\path\to\node_modules\mineflayer
```

The stage must use offline authentication and bind its game listener to loopback. The runner checks those settings before connecting. It never prints the RCON password. JSONL events and a summary are written under the stage's `folia-proof/` directory.
