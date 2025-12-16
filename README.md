# PaperMC/Spigot Minecraft Server Plugin Template
A template for building PaperMC/Spigot Minecraft server plugins!

<!-- TODO: CHANGE ME -->
[![Test and Release](https://github.com/CrimsonWarpedcraft/plugin-template/actions/workflows/main.yml/badge.svg)](https://github.com/CrimsonWarpedcraft/plugin-template/actions/workflows/main.yml)

<!-- TODO: CHANGE ME -->
[![](https://dcbadge.limes.pink/api/server/5XMmeV6EtJ)](https://discord.gg/5XMmeV6EtJ)

## Features
### Github Actions 🎬
* Automated builds, testing, and release drafting
* [Discord notifcations](https://github.com/marketplace/actions/discord-message-notify) for snapshots and releases

### Bots 🤖
* **Probot: Stale**
    * Mark issues stale after 30 days
* **Dependabot**
    * Update GitHub Actions workflows
    * Update Gradle dependencies

### Issue Templates 📋
* Bug report template
* Feature request template

### Gradle Builds 🏗
* Shadowed [PaperLib](https://github.com/PaperMC/PaperLib) build
* [Checkstyle](https://checkstyle.org/) Google standard style check
* [SpotBugs](https://spotbugs.github.io/) code analysis
* [JUnit](https://junit.org/) testing

### Config Files 📁
* Sample plugin.yml with autofill name, version, and main class.
* Empty config.yml (just to make life \*that\* much easier)
* Gradle build config
* Simple .gitignore for common Gradle files

## Usage
In order to use this template for yourself, there are a few things that you will need to keep in mind.

### Hosting the YouTube chat listener remotely
The bundled `chat_listener.py` script can be deployed on another machine and exposed over HTTP so
your Minecraft server only needs to poll a public URL. This is especially handy when you host the
listener on Hack Club's Nest platform where Caddy is provided out of the box.

1. Copy `src/main/resources/python/chat_listener.py` to the machine that will run the listener.
2. Install the optional dependencies you need (for example `pytchat` and `python-socketio`).
3. Decide on a directory for the service (e.g. `/opt/youtube-listener`) and place the script there.
4. (Optional) Create `/etc/youtube-listener.env` and set any secrets such as
   `STREAMLABS_SOCKET_TOKEN=...`.
5. Install the sample systemd unit from [`config/systemd/youtube-chat-listener.service`](config/systemd/youtube-chat-listener.service)
   and adjust the `WorkingDirectory`, `ExecStart`, `User` and `Group` directives to match your
   environment. The example unit is intended for a system service; if you prefer a user service,
   place it under `~/.config/systemd/user/` and adjust the commands accordingly. Reload the systemd
   daemon and enable the service:

   ```bash
   sudo cp config/systemd/youtube-chat-listener.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now youtube-chat-listener.service
   ```

6. When running behind Nest's Caddy instance, configure a reverse proxy so the listener is available
   on your public subdomain. The example [`config/caddy/youtube-listener.Caddyfile`](config/caddy/youtube-listener.Caddyfile)
   targets a listener bound to `127.0.0.1:8081` using the path prefix `/yt-listener`. Replace the
   placeholder domain with your actual host name (without an `http://` prefix) so Caddy can obtain
   certificates automatically, then reload Caddy: `sudo systemctl reload caddy`.

> ℹ️ For local development you can temporarily use `http://localhost`, but for public deployments
> you should rely on Caddy's automatic HTTPS to keep the listener secured.

The listener now understands the `--http-path-prefix` flag, allowing it to serve multiple paths such
as `/yt-listener` and `/yt-listener/events`, which plays nicely with Caddy route matchers. In the
plugin's `config.yml`, set `youtube.listener-url` to the HTTPS URL that Caddy exposes (for example
`https://example.hackclub.app/yt-listener/events`).

### YouTube listener management commands
Once the plugin is installed you can update the YouTube stream identifier in-game:

```text
/ytstream setchat <chatId|url>
/ytstream settarget <player>
/ytstream reload
```

If you are polling an externally hosted listener (`youtube.listener-url`), the plugin will also try
to push the new identifier to `POST /control/stream` on that listener. Set `LISTENER_CONTROL_TOKEN`
on the listener (or pass `--control-token`) and mirror the same token in
`youtube.listener-control-token` (or in the Minecraft server environment) to secure that endpoint.
To force using only a remote listener, set `youtube.local-listener-enabled: false`.

### TikTok listener integration
Alongside the YouTube bridge the plugin now supports events sourced from a TikTok listener. The new
`tiktok` block in `config.yml` mirrors the existing YouTube options so you can point the bridge at a
polling URL or external process, and `tiktok-bridge` exposes per-platform behaviour toggles (chat
commands, follower milestones, and gift-triggered orbital strikes). Use the `/ttstream` management
command to update the listener identifier or target player in-game:

```text
/ttstream setchat <listenerUrl>
/ttstream settarget <player>
/ttstream reload
```

Players who should receive TikTok chat relays require the `example.ttstream.monitor` permission
while administrators can manage the integration with `example.ttstream.use`. The plugin persists
follower counts to `tiktok-bridge-state` so milestone celebrations survive restarts just like the
YouTube bridge.

### Browser Overlay for OBS
When the HTTP endpoint is running, the listener also serves a lightweight overlay that you can add
as an OBS browser source. Point the source at the `/overlay` path matching your prefix, for example:

```text
http://127.0.0.1:8081/yt-listener/overlay
```

The overlay polls the `/events` endpoint, formats subscriber alerts in a Streamlabs-like card
(`{NAME} subscribed! Spawned 1 TNT.`), and shows the latest chat messages. Enable a transparent
background in OBS and place the browser source wherever you want alerts to appear on stream.

### Donation Orbital Strike
The plugin now reacts to Streamlabs donation events. By default any $5+ YouTube donation (currency
match is configurable) unleashes the **Orbital Strike Cannon**: 100 TNT spawns above the configured
target player and their screen flashes with a custom title. To tweak thresholds or visuals, edit the
`youtube-bridge.donations` section in `config.yml`—you can change the minimum amount, TNT count,
spawn height, wave size, or the title text (use placeholders like `{donor}`, `{formatted_amount}`,
and `{tnt_count}`). Trigger the Streamlabs “Test Donation” button to verify the full pipeline before
going live.

### Release Info
#### PaperMC Version Mapping
Here's a list of the PaperMC versions and the versions of this latest compatible version.

| PaperMC | ExamplePlugin |
|---------|---------------|
| 1.21.1  | 4.0.2         |
| 1.21    | 3.12.1        |
| 1.20.6  | 3.11.0        |
| 1.19.4  | 3.2.1         |
| 1.18.2  | 3.0.2         |
| 1.17.1  | 2.2.0         |
| 1.16.5  | 2.1.2         |

This chart would make more sense if this plugin actually did anything and people would have a reason
to be looking for older releases to run on older servers.

To use this as a template, just use the latest version of this project and update the PaperMC
version as needed. See more info on release stability below.

#### Release and Versioning Strategy
Stable versions of this repo are tagged `vX.Y.Z` and have an associated [release](https://github.com/CrimsonWarpedcraft/plugin-template/releases).

Testing versions of this repo are tagged `vX.Y.Z-RC-N` and have an associated [pre-release](https://github.com/CrimsonWarpedcraft/plugin-template/releases).

Development versions of this repo are pushed to the master branch and are **not** tagged.

| Event             | Plugin Version Format | CI Action                        | GitHub Release Draft? |
|-------------------|-----------------------|----------------------------------|-----------------------|
| PR                | yyMMdd-HHmm-SNAPSHOT  | Build and test                   | No                    |
| Cron              | yyMMdd-HHmm-SNAPSHOT  | Build, test, and notify          | No                    |
| Push to `main`    | 0.0.0-SNAPSHOT        | Build, test, release, and notify | No                    |
| Tag `vX.Y.Z-RC-N` | X.Y.Z-SNAPSHOT        | Build, test, release, and notify | Pre-release           |
| Tag `vX.Y.Z`      | X.Y.Z                 | Build, test, release, and notify | Release               |

### Discord Notifications
In order to use Discord notifications, you will need to create two GitHub secrets. `DISCORD_WEBHOOK_ID` 
should be set to the id of your Discord webhook. `DISCORD_WEBHOOK_TOKEN` will be the token for the webhook.

You can find these values by copying the Discord Webhook URL:  
`https://discord.com/api/webhooks/<DISCORD_WEBHOOK_ID>/<DISCORD_WEBHOOK_TOKEN>`

Optionally, you can also configure `DISCORD_RELEASE_WEBHOOK_ID` and `DISCORD_RELEASE_WEBHOOK_TOKEN`
to send release announcements to a separate channel.

For more information, see [Discord Message Notify](https://github.com/marketplace/actions/discord-message-notify).

---

**I've broken the rest of the changes up by their files to make things a bit easier to find.**

---

### settings.gradle
Update the line below with the name of your plugin.

```groovy
rootProject.name = 'ExamplePlugin'
```

### build.gradle
Make sure to update the `group` to your package's name in the following section.

```groovy
group = "com.crimsonwarpedcraft.exampleplugin"
```

Add any required repositories for your dependencies in the following section.

```groovy
repositories {
    maven {
        name 'papermc'
        url 'https://papermc.io/repo/repository/maven-public/'
        content {
            includeModule("io.papermc.paper", "paper-api")
            includeModule("io.papermc", "paperlib")
            includeModule("net.md-5", "bungeecord-chat")
        }
    }

    mavenCentral()
}
```

Also, update your dependencies as needed (of course).

```groovy
dependencies {
    compileOnly 'io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT'
    compileOnly 'com.github.spotbugs:spotbugs-annotations:4.9.8'
    implementation 'io.papermc:paperlib:1.0.8'
    spotbugsPlugins 'com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0'
    testCompileOnly 'com.github.spotbugs:spotbugs-annotations:4.9.8'
    testImplementation 'io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT'
    testImplementation platform('org.junit:junit-bom:6.0.0')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

### src/main/resources/plugin.yml
First, update the following with your information.

```yaml
author: AUTHOR
description: DESCRIPTION
```

Next, the `commands` and `permissions` sections below should be updated as needed.

```yaml
commands:
  ex:
    description: Base command for EXAMPLE
    usage: "For a list of commands, type /ex help"
    aliases: example
permissions:
  example.test:
    description: DESCRIPTION
    default: true
  example.*:
    description: Grants all other permissions
    default: false
    children:
      example.test: true
```

### .github/dependabot.yml
You will need to replace all instances of `leviem1`, such as the one below, with your GitHub
username.

```yaml
reviewers:
  - "leviem1"
```

### .github/CODEOWNERS
You will need to replace `leviem1`, with your GitHub username.

```text
*   @leviem1
```

### .github/FUNDING.yml
Update or delete this file, whatever applies to you.

```yaml
github: leviem1
```

For more information see: [Displaying a sponsor button in your repository](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/displaying-a-sponsor-button-in-your-repository)

### CODE_OF_CONDUCT.md
If you chose to adopt a Code of Conduct for your project, please update line 63 with your preferred
contact method.

## Creating a Release
Below are the steps you should follow to create a release.

1. Create a tag on `main` using semantic versioning (e.g. v0.1.0)
2. Push the tag and get some coffee while the workflows run
3. Publish the release draft once it's been automatically created

## Building locally
Thanks to [Gradle](https://gradle.org/), building locally is easy no matter what platform you're on. Simply run the following command:

```text
./gradlew build
```

This build step will also run all checks and tests, making sure your code is clean.

JARs can be found in `build/libs/`.

## Contributing
See [CONTRIBUTING.md](https://github.com/CrimsonWarpedcraft/plugin-template/blob/main/CONTRIBUTING.md).

---

I think that's all... phew! Oh, and update this README! ;)
