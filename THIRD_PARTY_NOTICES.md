# Third party components

JarStudio includes the following unmodified libraries and command line tools. Their original authors retain copyright. Exact artifact locations and SHA-256 hashes are recorded in `tools/dependencies.lock.json` (and in the application JAR under `META-INF/dependencies.lock.json`).

| Component | Version | Upstream license |
|---|---|---|
| ASM / ASM Tree / ASM Analysis / ASM Util | 9.11.1 | BSD 3-Clause |
| RSyntaxTextArea | 4.0.2 | BSD 3-Clause; original notice in its JAR |
| CFR | 0.152 | MIT |
| Vineflower | 1.12.0 | Apache 2.0 |
| Eclipse Java compiler (ECJ) | 3.33.0 | Eclipse Public License 2.0 |
| MicroEmulator / CLDC 1.1 API / MIDP 2.0 API | 2.0.4 | GNU LGPL 2.1 |
| Eclipse Temurin OpenJDK runtime / Java base API | 17.0.21.1+2 | OpenJDK license notices in the runtime `legal` directory |

Original license texts are included under `META-INF/licenses` in the runnable JAR, and in the `licenses` directory of the Windows distribution. The complete runtime retains its own `legal` directory. Embedded command line tools remain separate JARs in `tooling/`; extracted copies can be found under the application tools cache.

Source locations for bundled tools:

- ASM: https://gitlab.ow2.org/asm/asm
- RSyntaxTextArea: https://github.com/bobbylight/RSyntaxTextArea
- CFR: https://github.com/leibnitz27/cfr/tree/0.152
- Vineflower: https://github.com/Vineflower/vineflower/tree/1.12.0
- Eclipse JDT: https://github.com/eclipse-jdt/eclipse.jdt.core
- MicroEmulator: https://github.com/barteo/microemu and Maven Central source artifacts for version 2.0.4
- OpenJDK: https://github.com/adoptium/jdk17u and the runtime vendor source distribution

JarStudio does not bundle dnSpy code, names or artwork as application branding. Its interface follows familiar assembly explorer, editor and debugger conventions.
