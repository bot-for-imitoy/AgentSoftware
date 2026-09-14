# Multiple applications in one JVM

`Application` is the unit of isolation. Each instance owns:

- its `AppConfig` and `AppPaths`,
- its `TeamRuntime` (role workers) and `ClockService`,
- its `ToolService`/`ToolkitCatalog`,
- its `MailService`, `ComputerManager`, `ChatStore`,
- its note/todo/skill/state repositories under its own data directory.

Nothing in the runtime uses static mutable state, so two applications never see
each other's roles, tasks, clock or files.

```java
AppConfig config = ConfigLoader.load(
        AppPaths.resolve(AppConfig.defaults().storage()).configFile("config.json"))
        .toAppConfig();

Application companyA = Application.create(config, new ConsoleInputAdapter());
Application companyB = Application.create(config, new ConsoleInputAdapter());

companyA.hireDefaultRoles();
companyB.hireRoles(List.of("CEO", "COO", "HR"));

companyA.start();
companyB.start();
```

Give each application its own data root when they should not share files
(`storage.dataDir` in each config, i.e. `AGENTSOFTWARE_STORAGE_DATA_DIR`).

## Still shared on purpose

- The role-template registry is the read-only classpath resource
  `role_templates.json`.
- The provider catalog is the read-only classpath resource
  `providers.default.json`.
- Host-level podman infrastructure (the `maf-net` network and the
  `maf-base:latest` image) is per-host. Two podman-based applications on the same
  host must therefore use disjoint role sets — or `local` computers, each rooted
  in its own `base_dir`.

## Test coverage

`ApplicationEndToEndTest` starts two applications in one JVM and asserts that
their team, clock, tool service, chat store, notes and todos are independent, and
that starting one does not run the other's roles.
