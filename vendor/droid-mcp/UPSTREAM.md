# droid-mcp source provenance

- Repository: https://github.com/stixez/droid-mcp
- Revision: `aeaa5b9e8e96f56ef64a7ca23d0726585f7b1103`
- Imported: 2026-09-17
- License: Apache-2.0; see LICENSE in this directory.
- Imported modules: core, device, server-service, shell-core, root, shizuku, apps (including upstream tests).
- Root Gradle wrapper and version catalog also originate from this revision.
  The host version catalog is trimmed to dependencies used by the included modules;
  importing additional modules may require restoring their dependency aliases.

MBrain changes:

1. Core HTTP transport exposes a host parameter, defaulting to 127.0.0.1 for HTTP and TLS.
2. Server service exposes success/failure lifecycle callbacks for truthful UI status.
3. Local-only transport does not read Android device metadata for unused mDNS;
   added a real-socket regression test for authentication and stop behavior.
4. Core tools can expose full JSON Schema, receive raw JSON arguments and return
   lossless MCP content/structuredContent/isError; tool catalogs can be replaced atomically.
5. MBrain uses the upstream root authorization and Shizuku provider APIs, and the
   typed shell tools. App-owned ProcessSupervisor runs bounded, binary-safe child
   commands and owns their lifecycle rather than using the unbounded root exec path.

The upstream README is retained as reference; its full module list is not the MBrain feature list.
Other modules can be imported from this pinned revision when needed. Keep local changes
documented here when updating upstream. No nested Git repository or Git submodule is required.
