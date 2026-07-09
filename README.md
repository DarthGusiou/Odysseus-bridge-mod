# Odysseus Bridge

A client-side Fabric mod that connects Baritone to Odysseus over a **local WebSocket** — no server chat traffic, so Co-Pilot mode works on any server your client can join without spamming visible `[baritone]` messages.

## What it does

- Runs a WebSocket client on Minecraft launch that connects to `ws://127.0.0.1:7860/api/minecraft/copilot_bridge` (your local Odysseus)
- When Odysseus sends `{"type":"cmd","value":"goto 100 64 -50"}`, calls Baritone's programmatic command API — Baritone runs, your character moves
- When Baritone emits events (path start, goal reached, failed, cancelled), forwards them back to Odysseus as JSON
- Auto-reconnects if Odysseus restarts

**Nothing gets sent through Minecraft chat.**

## Building it (GitHub Actions — no local Java needed unless wanted)

Because you can't build Java projects locally, use GitHub to compile the jar for you. Full process:

### 1. Create a GitHub repo
- Log into github.com
- Click **+** → **New repository**
- Name it `odysseus-bridge-mod` (or anything)
- Leave everything default, click **Create repository**

### 2. Upload these files
Easy way — GitHub's web UI:
- On the new empty repo page, click **uploading an existing file**
- Drag the ENTIRE contents of `/Users/gusmiller/odysseus-bridge-mod/` (all files + folders) into the upload area
- Scroll down, click **Commit changes**

Or via terminal if you're comfortable:
```bash
cd /Users/gusmiller/odysseus-bridge-mod
git init -b main
git add .
git commit -m "Initial Odysseus Bridge"
git remote add origin https://github.com/YOUR_USERNAME/odysseus-bridge-mod.git
git push -u origin main
```

### 3. Watch it build
- Go to the **Actions** tab in your repo
- You'll see a workflow called **Build** running (or queued)
- Click into it — takes 3-5 minutes
- If green ✓ at the end, scroll down to **Artifacts** section
- Download **odysseus-bridge-jar** — it's a zip containing your jar

### 4. Install
- Unzip the downloaded artifact — you'll get `odysseus-bridge-0.1.0.jar`
- Drop it into your Minecraft `mods/` folder (same folder that has `baritone-meteor-26.1.jar`):
  ```
  ~/Library/Application Support/minecraft/mods/
  ```
- Launch Minecraft. Check the game log — you should see:
  ```
  [Odysseus Bridge 0.1.0 loading — MC 26.1]
  [Bridge WS connected → ws://127.0.0.1:7860/api/minecraft/copilot_bridge]
  ```

### 5. Confirm on Odysseus side
- Open the Minecraft AI panel
- You should see `ℹ Co-Pilot bridge connected — user=YOU, MC 26.1` in the feed
- Turn on **Silent mode** in Bot Settings (I'll add this toggle)

## Troubleshooting

### CI build fails
- Check the exact MC 26.1 mapping version — see `gradle.properties`. If Yarn `26.1+build.1` doesn't exist, find the actual build number at [fabricmc.net/develop](https://fabricmc.net/develop) and update `yarn_mappings=`.
- Fabric-API `0.135.0+26.1` might not exist — check [fabricmc.net/develop](https://fabricmc.net/develop) for the exact version tag.
- Baritone `1.11.0` might not have a 26.1 build yet — check [maven.meteordev.org/releases](https://maven.meteordev.org/releases) for the actual version. Update `baritone_version=`.

If any of these dependencies aren't available for MC 26.1, that means the modding ecosystem hasn't caught up yet — you may need to wait for updates or target 1.21.x instead.

### Mod loads but bridge doesn't connect
- Odysseus must be running on `127.0.0.1:7860` before you launch MC.
- Firewall could block localhost WebSocket in rare cases — check macOS System Settings → Network → Firewall.

### Baritone commands don't fire
- Verify `baritone-meteor-26.1.jar` is in the same `mods/` folder.
- Check MC log for `Baritone.execute(...) -> true` after a command is sent — that means Baritone accepted it.
- If Baritone accepted but nothing happens in-world, that's a Baritone-level problem (e.g. not in creative or spectator, no goal, etc.) — try `#goto ~ ~10 ~` in chat locally as a sanity check.

## What NOT to expect

- **Anti-cheat protection**: this mod hides the *coordination* channel between Odysseus and Baritone. It does not hide the *movement* itself. Server anti-cheat will still detect Baritone's superhuman pathing on premium servers. Only use on servers where Baritone is tolerated.

## License

MIT.
