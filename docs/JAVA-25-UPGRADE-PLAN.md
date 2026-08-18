# Java 25 Upgrade Plan

Branch: `claude/java-25-upgrade-gtpzj1`

## Current state

| Item | Value |
|---|---|
| Build | Maven, single module (`pom.xml`) |
| Parent | `spring-boot-starter-parent` 3.2.3 (latest 3.x is **3.5.16**; latest overall is 4.1.0) |
| Language level | `maven.compiler.source/target` = 17 (set twice: properties **and** compiler plugin) |
| Plugins | compiler 3.11.0, surefire 3.0.0, jar 3.3.0, spring-boot-maven-plugin (managed) |
| Test deps | explicit `junit-jupiter` 5.9.2 pin + `spring-boot-starter-test` |
| Main code | 7 classes, ~2.4 kLOC, plain Java + Spring MVC (`WebController`, `Application`, CLI `Main`) |
| Tests | 4 JUnit 5 classes, ~2.2 kLOC |
| CI | none (`.github/` does not exist) |
| Containerisation | none (no Dockerfile) |

Good news: no `SecurityManager`, `sun.misc.Unsafe`, `finalize()`, custom classloaders, agents, or
reflection into JDK internals. The application code itself is not the risk — the toolchain is.

## Verified empirically on JDK 25 (correction to an earlier assumption)

A JDK 25 build environment is now available (see *Toolchain setup* below), so the following is
measured rather than predicted. **An earlier draft of this plan claimed that Spring Boot 3.2.3's old
Byte Buddy/ASM/Mockito versions would block the build on Java 25. That is wrong for this project.**

Measured on OpenJDK 25.0.3:

| Configuration | Result |
|---|---|
| Unchanged `pom.xml` (Boot 3.2.3, `release` 17), built and tested on JDK 25 | **BUILD SUCCESS**, 114/114 tests pass |
| Boot 3.2.3, old plugins, `release` **25** | **BUILD SUCCESS**, 114/114 tests pass, class file major version 69 |
| Boot 3.5.16 + plugin bumps + `java.version` 25 | **BUILD SUCCESS**, 114/114 tests pass, no warnings |

Why the pessimistic prediction was wrong: the JDK-25 bytecode is emitted by `javac` itself, not by
the compiler plugin's bundled ASM, so `maven-compiler-plugin` 3.11.0 handles `release 25` fine. And
the Byte Buddy/Mockito concern never materialises because **these tests use no Mockito and no Spring
test context** — they are plain Jupiter assertions over file I/O, so the fragile machinery is never
loaded. Surefire 3.0.0 also forked cleanly.

This substantially de-risks the work: the language-level change alone is viable in isolation, and the
Spring Boot upgrade is a *maintenance* decision (3.2.3 is old and unsupported) rather than a
prerequisite. Both are still worth doing, and both are verified to work together — but they can now
be sequenced and reverted independently without fear.

## Toolchain setup

Dedicated JDK vendor hosts (`api.adoptium.net`, `corretto.aws`, `cdn.azul.com`,
`download.java.net`, `download.bell-sw.com`, `api.foojay.io`) are blocked by the agent proxy in this
environment, and the GitHub API is scoped to this repository only, so Temurin tarballs cannot be
downloaded here. The Ubuntu archive *is* reachable and noble ships OpenJDK 25:

```
apt-get update && apt-get install -y openjdk-25-jdk-headless   # 25.0.3+9-2~24.04.2
update-alternatives --set java  /usr/lib/jvm/java-25-openjdk-amd64/bin/java
update-alternatives --set javac /usr/lib/jvm/java-25-openjdk-amd64/bin/javac
```

Note that `JAVA_HOME` remains pointed at JDK 21 in this image, and Maven honours `JAVA_HOME` over
`update-alternatives`. Either export `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` or pass it per
invocation. For CI, `actions/setup-java` with Temurin 25 is the normal route and is unaffected by
this environment's proxy policy.

## Step 1 — Spring Boot upgrade to the latest 3.x (do this first, still on Java 17/21)

**Target: `spring-boot-starter-parent` 3.5.16** — the newest release on the 3.x line
(published 25 June 2026, i.e. *after* Boot 4.1.0, so the 3.5 line is still receiving releases).

This is the right target because it stays on the Spring Framework 6.2 / Jakarta EE 10 baseline, so
`spring-boot-starter-web`, `WebController` and `Application` need no code changes — while still
being a Boot release that is actively tested against Java 25. Verified from the 3.5.16 tag's own CI
matrix, which builds and tests on Java 17, 21 and **25** (25 via toolchain):

```
java:
  - version: 17
  - version: 21
  - version: 25   # toolchain: true
```

What 3.5.16 manages for us (read from `spring-boot-dependencies-3.5.16.pom`), replacing the
Java-25-hostile versions that Boot 3.2.3 pins:

| Managed dependency | Under 3.5.16 |
|---|---|
| Spring Framework | 6.2.19 |
| Tomcat (embedded) | 10.1.55 |
| Jackson | 2.21.4 |
| JUnit Jupiter | 5.12.2 |
| Mockito | 5.17.0 |
| Byte Buddy | 1.17.8 |

Concrete changes in `pom.xml`:
- `<parent>` version `3.2.3` → `3.5.16`.
- **Delete the explicit `junit-jupiter` 5.9.2 dependency.** It overrides the BOM with an old JUnit
  Platform, which is one of the things that would break on Java 25. `spring-boot-starter-test`
  already supplies Jupiter 5.12.2.

Compatibility assessment for 3.2.3 → 3.5.16 in *this* codebase:
- `application.properties` holds only the two multipart size settings; both keys are unchanged
  across 3.2 → 3.5.
- No deprecated-and-removed MVC APIs are in use, and there is no security, data, actuator or
  observability configuration to migrate — the surface area of this upgrade is unusually small.
- The tests import only `org.junit.jupiter.api.*`, `Assertions.*` and `@TempDir`, with **no Mockito
  and no Spring test context**. So the Jupiter 5.9 → 5.12 jump is source-compatible here, and the
  Byte Buddy / Mockito / agent-loading concerns (Step 4) are effectively moot for these tests —
  confirm rather than assume, but do not expect trouble.

### Why not Spring Boot 4.x?

4.1.0 is the current overall release. It is deliberately **out of scope for this branch**: it moves
to Jakarta EE 11 and carries relocated/removed APIs and configuration-property changes, so it is its
own piece of work with its own testing. Bundling it with the Java 25 change would make a failure
impossible to attribute. Land Java 25 on 3.5.16 first; treat Boot 4.x as a follow-up.

Validate with a full `mvn clean verify` on the *current* JDK before changing the language level, so
that any breakage is unambiguously attributable to the Boot upgrade.

## Step 2 — Language level and toolchain

In `pom.xml`:
- Set `<java.version>25</java.version>` (the Boot parent wires this into `maven.compiler.release`).
- Delete the `maven.compiler.source` / `maven.compiler.target` properties and the explicit
  `<source>`/`<target>` block in the compiler plugin. Duplicating them in three places is how
  language levels drift; prefer a single `release`-based setting.
- Keep an explicit compiler plugin entry only if a plugin version bump is needed (see Step 3).

Optionally add `maven-toolchains-plugin` so the build fails fast with a clear message on a wrong JDK
rather than emitting a confusing "invalid target release" error.

## Step 3 — Plugin and test-dependency bumps

Latest stable releases, confirmed against Maven Central (skip the 4.0.0-beta / 3.6.0-M1 lines —
they are pre-releases):

- `maven-compiler-plugin` 3.11.0 → **3.15.0**. Older versions ship an ASM that cannot read class
  file 69.
- `maven-surefire-plugin` 3.0.0 → **3.5.6**. 3.0.0's fork/JUnit-platform provider is a known failure
  point on recent JDKs.
- `maven-jar-plugin` 3.3.0 → **3.5.1** (low risk, keeps the `Main-Class` manifest as-is).
- **Remove the explicit `junit-jupiter` 5.9.2 dependency.** It fights the Boot BOM and pins an old
  JUnit Platform. `spring-boot-starter-test` already brings a managed, JDK-25-compatible Jupiter.

## Step 4 — Java 25 runtime behaviour

Two integrity-by-default changes affect test/run output rather than correctness:
- **Dynamic agent loading** is warned about (and headed for denial). Mockito's inline mock maker
  self-attaches. If the tests use Mockito, add `-XX:+EnableDynamicAgentLoading` to surefire
  `argLine`, or better, declare `mockito-core` as a `-javaagent` via surefire. Current tests appear
  to be plain JUnit assertions, so this may be a no-op — confirm during the build.
- **`sun.misc.Unsafe` memory-access warnings** may surface from transitive libraries. Note them;
  they are the dependency owner's to fix, not ours.

Also re-check the 500 MB multipart limits behave the same — these are large-file extractions, and
worth one real upload test rather than trusting unit tests.

## Step 5 — Verification

1. `mvn -U clean verify` on JDK 25 — all four test classes green.
2. Confirm the produced jar's class file version is 69 (`javap -v` on one class).
3. Smoke-test the CLI paths documented in the README: `extract-terms`, `extract-tags`, `validate`,
   `list-validations`, `exception-list add|remove|list`.
4. Start the Boot app and exercise the web UI upload path (`src/main/resources/static/index.html`)
   against a real RF2 file.

Steps 1 and 2 are **already confirmed** on OpenJDK 25.0.3 in this environment (114/114 tests green,
class file major version 69) for both the language-level-only change and the full Boot 3.5.16
upgrade. Steps 3 and 4 remain genuinely manual — the tests cover file parsing, not the CLI argument
surface or the 500 MB multipart upload path.

## Step 6 — Documentation and CI

- README: build prerequisites currently imply Java 17; state Java 25 and Spring Boot 3.5.16.
- There is no CI at all. Adding a minimal GitHub Actions workflow (`setup-java` with Temurin 25 +
  `mvn verify`) as part of this branch is the cheapest way to keep the upgrade from silently
  regressing, and gives the verification above a permanent home.

## Sequencing and risk

Recommended commit sequence, each independently buildable:

1. Boot parent 3.2.3 → **3.5.16**, drop the JUnit 5.9.2 pin. *(highest-risk step, isolated)*
2. Plugin version bumps.
3. Language level 17 → 25, remove duplicated source/target settings.
4. README/docs + CI workflow.

Rollback is per-commit. With steps 1–3 now verified green on JDK 25, the residual risk is narrower
than first assessed: the large-file upload path and the CLI subcommands are exercised by neither the
unit tests nor this verification, which is why steps 5.3 and 5.4 stay manual checks. No transitive
dependency turned out to lack a Java 25-ready release.
