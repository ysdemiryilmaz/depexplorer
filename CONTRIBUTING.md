# Contributing to Dependency Explorer

Thank you for taking the time to help. Bug reports, ideas and pull requests are all welcome.

## Reporting a bug

Open an issue with the **Bug report** template. These details make a bug much easier to reproduce:

- the engine and its version (for example *OIE 4.6.0*),
- the plugin version (*Settings › Extensions*),
- which administrator you used: the classic Administrator, the Administrator Launcher, or the OIE Web Administrator,
- what you did, what you expected and what happened,
- the relevant part of the server log (`logs/mirth.log`) or of the Administrator console.

Please remove patient data, passwords, host names and other confidential values from logs and screenshots. If you
need a channel to reproduce the problem, export a small sample channel without real data.

Do not report security vulnerabilities in public issues; see [SECURITY.md](SECURITY.md).

## Suggesting a feature

Open an issue with the **Feature request** template. Describe the problem you want to solve, not only the
solution you have in mind.

## Pull requests

1. For anything larger than a small fix, open an issue first so we can agree on the approach.
2. Fork the repository and create a branch from `main`.
3. Build and test your change (see below).
4. Keep the pull request focused on one change, and fill in the pull request template.

### Building

Requirements: JDK 11 or newer, and an OIE / Mirth Connect 4.5.2+ installation to compile against.

```
java Build.java --mirth /opt/mirthconnect
```

The result is `dist/depexplorer-<version>.zip`. If you change the web administrator part
(`webadmin/web/plugin.tsx`), rebuild it with Node.js first:

```
cd webadmin
npm install
npm run build
```

Commit the rebuilt `webadmin/web/plugin.js` together with your change, so the plugin can still be built without
Node.js.

### Guidelines

- The plugin must keep running on **OIE 4.5.2**: Java code is compiled for Java 8, so do not use newer Java APIs.
- The plugin is **read-only**. It must never change the configuration of the engine.
- Code that touches Mirth internals (reflection on editor or cache classes) must fail gracefully and fall back to
  something that still works.
- Keep large servers in mind: the plugin is used on servers with hundreds of channels.
- The user interface text is in English.
- Never commit the signing keystore (`keystore/`) or build output (`build/`, `dist/`, `node_modules/`).

### Testing

Before you open a pull request, install the built zip on a test server and check at least:

- *Settings › Dependency Explorer* opens, and the Explore, Flow Graph, Problems and Unused tabs work,
- *Channel Tasks › View Dependencies* opens for a channel,
- double-clicking a result opens the right place in the editor,
- if you changed the web part: the same in the OIE Web Administrator.

Mention in the pull request which engine versions you tested on.

## Code of conduct

By taking part in this project, you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
