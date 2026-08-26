# Aura M1 — скелет и события: план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Приложение в трее принимает задачу текстом, отправляет её долгоживущему процессу `claude` или `codex`, нормализует поток событий агента и требует подтверждения для опасных вызовов — всё без единой нейросети.

**Architecture:** Maven multi-module на Java 21. `aura-core` держит доменные типы без зависимостей. `aura-agents` превращает JSONL двух разных CLI в единый `AgentEvent`. `aura-policy` классифицирует вызовы инструментов. `aura-ipc` соединяет процесс хука с приложением через сокет AF_UNIX. `aura-app` собирает всё и рисует трей. Сайдкар на этом этапе — заглушка на Python, которая говорит по протоколу, но не грузит моделей.

**Tech Stack:** Java 21, Maven 3.9, Jackson 2.17, SnakeYAML 2.2, SLF4J 2.0 + Logback 1.5, JUnit 5.10, AssertJ 3.25, Swing (трей и диалоги), Python 3.13 (заглушка сайдкара).

**Spec:** [`docs/superpowers/specs/2026-08-26-aura-design.md`](../specs/2026-08-26-aura-design.md)

## Global Constraints

- **Java 21**, `maven.compiler.release=21`. Никаких preview-фич.
- **Корневой пакет `aura`**, по модулю на подпакет: `aura.core`, `aura.agents`, `aura.policy`, `aura.ipc`, `aura.app`, `aura.hook`.
- **`aura-core` не имеет зависимостей времени выполнения.** Ни Jackson, ни SnakeYAML, ни SLF4J. Сырой JSON хранится в событии строкой, а не деревом.
- **`bypassPermissions` не используется никогда** — ни в коде, ни в конфигах, ни в тестах.
- **Отказ по умолчанию.** Таймаут, ошибка сокета, нераспознанный ответ — всё это `DENY`. `ALLOW` возвращается только при явном согласии.
- **Тесты не ходят в сеть и не поднимают настоящий `claude`.** Процессные тесты используют поддельного агента из тестовых исходников. Ровно два шага плана запускают настоящий агент, и оба помечены явно: Task 10 Step 1 (проверка контракта хука, закрывает RISK-6) и Task 15 Step 9 (сквозная проверка цепочки).
- **Фикстуры в `testdata/fixtures/` — снятые с живых процессов, менять их нельзя.** Если адаптер не сходится с фикстурой, чинится адаптер.
- **Все пути к файлам в коде строятся через `Path.of`**, без конкатенации строк с разделителями.
- Модуль `aura-narration` в M1 не создаётся — он относится к M3.

---

## Структура файлов

```
C:\Aura\aura   (корень репозитория)
├─ pom.xml                                  родительский POM, версии зависимостей
├─ aura-core/
│  ├─ pom.xml
│  └─ src/main/java/aura/core/
│     ├─ Agent.java                         CLAUDE | CODEX
│     ├─ EventKind.java                     вид события
│     ├─ ToolClass.java                     класс инструмента + распознавание по имени
│     ├─ AgentEvent.java                    канонический тип события + Builder
│     ├─ Project.java                       описание проекта из реестра
│     └─ ProjectRegistry.java               поиск проекта по имени и по фразе
├─ aura-agents/
│  ├─ pom.xml
│  └─ src/main/java/aura/agents/
│     ├─ ClaudeEventParser.java             stream-json → AgentEvent
│     ├─ CodexEventParser.java              exec --json → AgentEvent
│     ├─ TestResultDetector.java            распознавание результата тестов
│     ├─ AgentSession.java                  интерфейс сессии
│     ├─ ClaudeSession.java                 долгоживущий процесс claude
│     ├─ CodexSession.java                  повторный запуск codex exec resume
│     ├─ SessionConfig.java                 параметры запуска
│     └─ SessionSupervisor.java             жизненный цикл, перезапуск, убийство дерева
├─ aura-policy/
│  ├─ pom.xml
│  └─ src/main/java/aura/policy/
│     ├─ Decision.java                      ALLOW | CONFIRM | DENY
│     ├─ ToolRequest.java                   запрос инструмента от агента
│     ├─ PermissionPolicy.java              классификация по реестру проекта
│     ├─ ConfirmationProvider.java          интерфейс подтверждения
│     └─ TrayConfirmationProvider.java      модальный диалог, таймаут → DENY
├─ aura-ipc/
│  ├─ pom.xml
│  └─ src/main/java/aura/ipc/
│     ├─ HookRequest.java                   запрос от хука
│     ├─ HookResponse.java                  вердикт хуку
│     ├─ HookServer.java                    сервер AF_UNIX
│     ├─ HookClient.java                    клиент AF_UNIX
│     └─ SpeechClient.java                  протокол сайдкара по stdio
├─ aura-hook/
│  ├─ pom.xml
│  └─ src/main/java/aura/hook/
│     └─ HookMain.java                      исполняемый файл для PreToolUse
└─ aura-app/
   ├─ pom.xml
   └─ src/main/java/aura/app/
      ├─ AuraConfig.java                    config.yaml
      ├─ ProjectRegistryLoader.java         projects.yaml → ProjectRegistry
      ├─ SettingsFileWriter.java            временный settings.json для агента
      ├─ TaskDispatcher.java                фраза → проект → сессия
      ├─ TrayApp.java                       иконка, меню, диалог ввода
      └─ Main.java                          точка входа

├─ sidecar/
│  └─ aura_speech/
│     └─ stub.py                            заглушка протокола без моделей
├─ testdata/
│  └─ fixtures/                             потоки событий, снятые с живых CLI
└─ docs/                                    PRD, дизайн, ADR, риски, планы
```

Границы держатся так: `aura-core` не знает про JSON и процессы, `aura-agents` не знает про трей, `aura-policy` не знает, кто задаёт вопрос пользователю, `aura-ipc` не знает, что означают инструменты. Всё связывается только в `aura-app`.

---

## Task 1: Каркас сборки

**Files:**
- Create: `pom.xml`
- Create: `aura-core/pom.xml`, `aura-agents/pom.xml`, `aura-policy/pom.xml`, `aura-ipc/pom.xml`, `aura-hook/pom.xml`, `aura-app/pom.xml`
- Test: `aura-core/src/test/java/aura/core/BuildSmokeTest.java`

**Interfaces:**
- Consumes: ничего
- Produces: собираемый проект; все последующие задачи выполняются командой `mvn test`

- [ ] **Step 1: Написать падающий тест**

Создать `aura-core/src/test/java/aura/core/BuildSmokeTest.java`:

```java
package aura.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSmokeTest {

    @Test
    void javaVersionIs21OrHigher() {
        int major = Runtime.version().feature();
        assertThat(major).isGreaterThanOrEqualTo(21);
    }

    @Test
    void afUnixSocketsAreAvailableOnThisPlatform() throws Exception {
        // Вся связь с процессом хука построена на AF_UNIX. Если платформа его не
        // поддерживает, узнать об этом надо на сборке, а не в проде.
        try (var ch = java.nio.channels.ServerSocketChannel.open(
                java.net.StandardProtocolFamily.UNIX)) {
            assertThat(ch.isOpen()).isTrue();
        }
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `mvn -q test`
Expected: FAIL — `pom.xml` не существует, Maven сообщает `The specified user settings file does not exist` или `Non-readable POM`.

- [ ] **Step 3: Написать родительский POM**

Создать `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>aura</groupId>
  <artifactId>aura-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <name>Aura</name>

  <modules>
    <module>aura-core</module>
    <module>aura-agents</module>
    <module>aura-policy</module>
    <module>aura-ipc</module>
    <module>aura-hook</module>
    <module>aura-app</module>
  </modules>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <jackson.version>2.17.2</jackson.version>
    <snakeyaml.version>2.2</snakeyaml.version>
    <slf4j.version>2.0.13</slf4j.version>
    <logback.version>1.5.6</logback.version>
    <junit.version>5.10.2</junit.version>
    <assertj.version>3.25.3</assertj.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>${jackson.version}</version>
      </dependency>
      <dependency>
        <groupId>org.yaml</groupId>
        <artifactId>snakeyaml</artifactId>
        <version>${snakeyaml.version}</version>
      </dependency>
      <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>${slf4j.version}</version>
      </dependency>
      <dependency>
        <groupId>ch.qos.logback</groupId>
        <artifactId>logback-classic</artifactId>
        <version>${logback.version}</version>
      </dependency>
      <dependency>
        <groupId>org.junit</groupId>
        <artifactId>junit-bom</artifactId>
        <version>${junit.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>org.assertj</groupId>
        <artifactId>assertj-core</artifactId>
        <version>${assertj.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-surefire-plugin</artifactId>
          <version>3.2.5</version>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>
</project>
```

- [ ] **Step 4: Написать POM каждого модуля**

`aura-core/pom.xml` — единственный модуль без зависимостей времени выполнения:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>aura</groupId>
    <artifactId>aura-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>aura-core</artifactId>
</project>
```

`aura-agents/pom.xml`, `aura-policy/pom.xml`, `aura-ipc/pom.xml` — одинаковы по форме, отличаются только `<artifactId>`; каждый добавляет `aura-core`, Jackson и SLF4J:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>aura</groupId>
    <artifactId>aura-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>aura-agents</artifactId>

  <dependencies>
    <dependency>
      <groupId>aura</groupId>
      <artifactId>aura-core</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
  </dependencies>
</project>
```

Для `aura-policy` заменить `<artifactId>` на `aura-policy`. Для `aura-ipc` — на `aura-ipc`.

`aura-hook/pom.xml` зависит от `aura-ipc` и собирается в исполняемый jar:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>aura</groupId>
    <artifactId>aura-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>aura-hook</artifactId>

  <dependencies>
    <dependency>
      <groupId>aura</groupId>
      <artifactId>aura-ipc</artifactId>
      <version>${project.version}</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.2</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <finalName>aura-hook</finalName>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>aura.hook.HookMain</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

`aura-app/pom.xml` зависит от всех модулей и от SnakeYAML с Logback:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>aura</groupId>
    <artifactId>aura-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>aura-app</artifactId>

  <dependencies>
    <dependency>
      <groupId>aura</groupId>
      <artifactId>aura-agents</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>aura</groupId>
      <artifactId>aura-policy</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>aura</groupId>
      <artifactId>aura-ipc</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.yaml</groupId>
      <artifactId>snakeyaml</artifactId>
    </dependency>
    <dependency>
      <groupId>ch.qos.logback</groupId>
      <artifactId>logback-classic</artifactId>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.2</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <finalName>aura-app</finalName>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>aura.app.Main</mainClass>
                </transformer>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 5: Запустить тесты и убедиться, что проходят**

Run: `mvn -q test`
Expected: PASS, два теста в `BuildSmokeTest`, шесть модулей собраны.

Если Maven не находит версию зависимости — поднять её в пределах той же минорной ветки в `<properties>` родительского POM и повторить. Версии в списке существуют на момент написания плана; сеть или зеркало могут отличаться.

- [ ] **Step 6: Закоммитить**

```bash
git add -A
git commit -m "build: add Maven multi-module skeleton for M1"
```

---

## Task 2: Доменные типы события

**Files:**
- Create: `aura-core/src/main/java/aura/core/Agent.java`
- Create: `aura-core/src/main/java/aura/core/EventKind.java`
- Create: `aura-core/src/main/java/aura/core/ToolClass.java`
- Create: `aura-core/src/main/java/aura/core/AgentEvent.java`
- Test: `aura-core/src/test/java/aura/core/ToolClassTest.java`
- Test: `aura-core/src/test/java/aura/core/AgentEventTest.java`

**Interfaces:**
- Consumes: ничего
- Produces:
  - `enum Agent { CLAUDE, CODEX }`
  - `enum EventKind { SESSION_START, ASSISTANT_TEXT, TOOL_START, TOOL_END, ERROR, TEST_RESULT, PERMISSION_REQUEST, SUBAGENT_START, SUBAGENT_END, DONE, OTHER }`
  - `enum ToolClass { SEARCH, READ, EDIT, EXEC, NET, TASK, OTHER }` со статическим `ToolClass of(String toolName)`
  - `record AgentEvent(Instant ts, String sessionId, Agent agent, EventKind kind, ToolClass toolClass, String target, Boolean ok, String summaryHint, String toolUseId, String parentToolUseId, String raw)` с вложенным `AgentEvent.Builder`, создаваемым через `AgentEvent.builder()`

- [ ] **Step 1: Написать падающий тест на классификацию инструментов**

Создать `aura-core/src/test/java/aura/core/ToolClassTest.java`:

```java
package aura.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ToolClassTest {

    @ParameterizedTest
    @CsvSource({
        "Grep,     SEARCH",
        "Glob,     SEARCH",
        "Read,     READ",
        "Edit,     EDIT",
        "Write,    EDIT",
        "NotebookEdit, EDIT",
        "Bash,     EXEC",
        "PowerShell,   EXEC",
        "WebFetch, NET",
        "WebSearch,    NET",
        "Task,     TASK",
        "Skill,    OTHER"
    })
    void mapsClaudeToolNames(String toolName, ToolClass expected) {
        assertThat(ToolClass.of(toolName)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "command_execution, EXEC",
        "file_change,       EDIT",
        "web_search,        NET",
        "mcp_tool_call,     OTHER",
        "reasoning,         OTHER"
    })
    void mapsCodexItemTypes(String itemType, ToolClass expected) {
        assertThat(ToolClass.of(itemType)).isEqualTo(expected);
    }

    @Test
    void unknownNameIsOtherAndNeverThrows() {
        assertThat(ToolClass.of("СовершенноНовыйИнструмент")).isEqualTo(ToolClass.OTHER);
        assertThat(ToolClass.of(null)).isEqualTo(ToolClass.OTHER);
        assertThat(ToolClass.of("")).isEqualTo(ToolClass.OTHER);
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-core test`
Expected: FAIL — компиляция не проходит, `cannot find symbol: class ToolClass`.

- [ ] **Step 3: Написать перечисления**

`aura-core/src/main/java/aura/core/Agent.java`:

```java
package aura.core;

/** Какой CLI породил событие. */
public enum Agent {
    CLAUDE,
    CODEX
}
```

`aura-core/src/main/java/aura/core/EventKind.java`:

```java
package aura.core;

/**
 * Вид события в канонической форме. Оба адаптера обязаны укладываться в этот
 * набор: всё, что не распознано, становится {@link #OTHER} и не роняет поток.
 */
public enum EventKind {
    SESSION_START,
    ASSISTANT_TEXT,
    TOOL_START,
    TOOL_END,
    ERROR,
    TEST_RESULT,
    PERMISSION_REQUEST,
    SUBAGENT_START,
    SUBAGENT_END,
    DONE,
    OTHER
}
```

`aura-core/src/main/java/aura/core/ToolClass.java`:

```java
package aura.core;

import java.util.Locale;
import java.util.Map;

/**
 * Класс инструмента. Нарратор говорит о смене класса, а не о каждом вызове,
 * поэтому классификация — часть домена, а не деталь адаптера.
 */
public enum ToolClass {
    SEARCH,
    READ,
    EDIT,
    EXEC,
    NET,
    TASK,
    OTHER;

    private static final Map<String, ToolClass> BY_NAME = Map.ofEntries(
        // Claude Code
        Map.entry("grep", SEARCH),
        Map.entry("glob", SEARCH),
        Map.entry("read", READ),
        Map.entry("notebookread", READ),
        Map.entry("edit", EDIT),
        Map.entry("write", EDIT),
        Map.entry("multiedit", EDIT),
        Map.entry("notebookedit", EDIT),
        Map.entry("bash", EXEC),
        Map.entry("powershell", EXEC),
        Map.entry("webfetch", NET),
        Map.entry("websearch", NET),
        Map.entry("task", TASK),
        // Codex: типы элементов из exec --json
        Map.entry("command_execution", EXEC),
        Map.entry("file_change", EDIT),
        Map.entry("web_search", NET),
        Map.entry("mcp_tool_call", OTHER),
        Map.entry("reasoning", OTHER)
    );

    /** Никогда не бросает и никогда не возвращает null: неизвестное имя — это {@link #OTHER}. */
    public static ToolClass of(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return OTHER;
        }
        return BY_NAME.getOrDefault(toolName.toLowerCase(Locale.ROOT), OTHER);
    }
}
```

- [ ] **Step 4: Запустить тест классификации и убедиться, что проходит**

Run: `mvn -q -pl aura-core test`
Expected: PASS.

- [ ] **Step 5: Написать падающий тест на событие**

Создать `aura-core/src/test/java/aura/core/AgentEventTest.java`:

```java
package aura.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentEventTest {

    @Test
    void builderFillsDefaultsForOptionalFields() {
        AgentEvent e = AgentEvent.builder()
            .ts(Instant.parse("2026-08-26T10:00:00Z"))
            .sessionId("s1")
            .agent(Agent.CLAUDE)
            .kind(EventKind.TOOL_START)
            .toolClass(ToolClass.EXEC)
            .target("echo hi")
            .build();

        assertThat(e.ok()).isNull();
        assertThat(e.summaryHint()).isEmpty();
        assertThat(e.parentToolUseId()).isNull();
        assertThat(e.raw()).isEmpty();
    }

    @Test
    void requiredFieldsAreEnforced() {
        assertThatThrownBy(() -> AgentEvent.builder().build())
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void subagentEventIsRecognisedByParentId() {
        AgentEvent own = AgentEvent.builder()
            .ts(Instant.EPOCH).sessionId("s").agent(Agent.CLAUDE)
            .kind(EventKind.TOOL_START).toolClass(ToolClass.READ).target("a.java")
            .build();
        AgentEvent nested = AgentEvent.builder()
            .ts(Instant.EPOCH).sessionId("s").agent(Agent.CLAUDE)
            .kind(EventKind.TOOL_START).toolClass(ToolClass.READ).target("b.java")
            .parentToolUseId("toolu_123")
            .build();

        assertThat(own.fromSubagent()).isFalse();
        assertThat(nested.fromSubagent()).isTrue();
    }
}
```

- [ ] **Step 6: Написать `AgentEvent`**

`aura-core/src/main/java/aura/core/AgentEvent.java`:

```java
package aura.core;

import java.time.Instant;
import java.util.Objects;

/**
 * Каноническое событие агента. Оба CLI сводятся к нему, и всё, что выше
 * адаптеров, знает только этот тип.
 *
 * <p>{@code summaryHint} — намеренно короткая выжимка для будущего нарратора,
 * а не полный вывод инструмента. {@code raw} хранится строкой, а не деревом,
 * чтобы модуль остался без зависимости на JSON-библиотеку.
 */
public record AgentEvent(
    Instant ts,
    String sessionId,
    Agent agent,
    EventKind kind,
    ToolClass toolClass,
    String target,
    Boolean ok,
    String summaryHint,
    String toolUseId,
    String parentToolUseId,
    String raw
) {

    public AgentEvent {
        Objects.requireNonNull(ts, "ts");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(toolClass, "toolClass");
        Objects.requireNonNull(target, "target");
        summaryHint = summaryHint == null ? "" : summaryHint;
        raw = raw == null ? "" : raw;
    }

    /** Событие порождено подзадачей, а не основным потоком. */
    public boolean fromSubagent() {
        return parentToolUseId != null && !parentToolUseId.isBlank();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Instant ts;
        private String sessionId;
        private Agent agent;
        private EventKind kind;
        private ToolClass toolClass = ToolClass.OTHER;
        private String target = "";
        private Boolean ok;
        private String summaryHint = "";
        private String toolUseId;
        private String parentToolUseId;
        private String raw = "";

        public Builder ts(Instant v) { this.ts = v; return this; }
        public Builder sessionId(String v) { this.sessionId = v; return this; }
        public Builder agent(Agent v) { this.agent = v; return this; }
        public Builder kind(EventKind v) { this.kind = v; return this; }
        public Builder toolClass(ToolClass v) { this.toolClass = v; return this; }
        public Builder target(String v) { this.target = v; return this; }
        public Builder ok(Boolean v) { this.ok = v; return this; }
        public Builder summaryHint(String v) { this.summaryHint = v; return this; }
        public Builder toolUseId(String v) { this.toolUseId = v; return this; }
        public Builder parentToolUseId(String v) { this.parentToolUseId = v; return this; }
        public Builder raw(String v) { this.raw = v; return this; }

        public AgentEvent build() {
            return new AgentEvent(ts, sessionId, agent, kind, toolClass, target,
                ok, summaryHint, toolUseId, parentToolUseId, raw);
        }
    }
}
```

- [ ] **Step 7: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-core test`
Expected: PASS, все тесты `ToolClassTest` и `AgentEventTest`.

- [ ] **Step 8: Закоммитить**

```bash
git add aura-core/
git commit -m "feat(core): add canonical AgentEvent and tool classification"
```

---

## Task 3: Адаптер Claude Code

**Files:**
- Create: `aura-agents/src/main/java/aura/agents/ClaudeEventParser.java`
- Test: `aura-agents/src/test/java/aura/agents/ClaudeEventParserTest.java`
- Читает: `testdata/fixtures/claude-stream-json-tool-call.jsonl` (менять нельзя)

**Interfaces:**
- Consumes: `AgentEvent`, `AgentEvent.builder()`, `Agent.CLAUDE`, `EventKind`, `ToolClass.of(String)` из Task 2
- Produces: `class ClaudeEventParser` с конструктором `ClaudeEventParser(Clock clock)` и методом `List<AgentEvent> parseLine(String jsonLine)`. Парсер **stateful**: помнит соответствие `tool_use_id → (ToolClass, target)`, чтобы у результата вызова был класс инструмента. Один экземпляр на сессию.

- [ ] **Step 1: Написать падающий тест на фикстуре**

Создать `aura-agents/src/test/java/aura/agents/ClaudeEventParserTest.java`:

```java
package aura.agents;

import static org.assertj.core.api.Assertions.assertThat;

import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.EventKind;
import aura.core.ToolClass;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaudeEventParserTest {

    private static final Path FIXTURE = Path.of("..", "testdata", "fixtures",
        "claude-stream-json-tool-call.jsonl");

    private static final Clock FIXED =
        Clock.fixed(Instant.parse("2026-08-26T10:00:00Z"), ZoneOffset.UTC);

    private List<AgentEvent> parseFixture() throws Exception {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        List<AgentEvent> all = new ArrayList<>();
        for (String line : Files.readAllLines(FIXTURE, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                all.addAll(parser.parseLine(line));
            }
        }
        return all;
    }

    @Test
    void dropsHookNoiseLines() throws Exception {
        List<AgentEvent> events = parseFixture();
        // В фикстуре есть строки system/hook_started и system/hook_response.
        // Ни одна из них не должна дожить до канонического потока.
        assertThat(events)
            .noneMatch(e -> e.raw().contains("\"hook_started\""))
            .noneMatch(e -> e.raw().contains("\"hook_response\""));
    }

    @Test
    void producesSessionStartWithSessionId() throws Exception {
        AgentEvent first = parseFixture().get(0);
        assertThat(first.kind()).isEqualTo(EventKind.SESSION_START);
        assertThat(first.agent()).isEqualTo(Agent.CLAUDE);
        assertThat(first.sessionId()).isNotBlank();
    }

    @Test
    void producesToolStartForBashWithCommandAsTarget() throws Exception {
        AgentEvent start = parseFixture().stream()
            .filter(e -> e.kind() == EventKind.TOOL_START)
            .findFirst()
            .orElseThrow();

        assertThat(start.toolClass()).isEqualTo(ToolClass.EXEC);
        assertThat(start.target()).isEqualTo("echo hi");
        assertThat(start.toolUseId()).startsWith("toolu_");
    }

    @Test
    void toolEndInheritsClassFromMatchingStart() throws Exception {
        AgentEvent end = parseFixture().stream()
            .filter(e -> e.kind() == EventKind.TOOL_END)
            .findFirst()
            .orElseThrow();

        // Класс не написан в строке результата — он берётся из запомненного вызова.
        assertThat(end.toolClass()).isEqualTo(ToolClass.EXEC);
        assertThat(end.ok()).isTrue();
        assertThat(end.summaryHint()).contains("hi");
    }

    @Test
    void finalResultLineBecomesDoneCarryingAnswerText() throws Exception {
        List<AgentEvent> events = parseFixture();
        AgentEvent last = events.get(events.size() - 1);

        assertThat(last.kind()).isEqualTo(EventKind.DONE);
        assertThat(last.ok()).isTrue();
        assertThat(last.summaryHint()).isEqualTo("done");
    }

    @Test
    void toolResultWithErrorBecomesErrorEvent() {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        parser.parseLine("""
            {"type":"system","subtype":"init","session_id":"s1","cwd":"C:/x"}""");
        parser.parseLine("""
            {"type":"assistant","session_id":"s1","message":{"content":[
              {"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"pytest"}}]}}""");

        List<AgentEvent> events = parser.parseLine("""
            {"type":"user","session_id":"s1","message":{"content":[
              {"type":"tool_result","tool_use_id":"toolu_1","content":"boom","is_error":true}]}}""");

        assertThat(events).singleElement()
            .satisfies(e -> {
                assertThat(e.kind()).isEqualTo(EventKind.ERROR);
                assertThat(e.ok()).isFalse();
                assertThat(e.toolClass()).isEqualTo(ToolClass.EXEC);
            });
    }

    @Test
    void taskToolBecomesSubagentStartAndItsChildrenCarryParentId() {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        parser.parseLine("""
            {"type":"system","subtype":"init","session_id":"s1","cwd":"C:/x"}""");

        List<AgentEvent> started = parser.parseLine("""
            {"type":"assistant","session_id":"s1","message":{"content":[
              {"type":"tool_use","id":"toolu_task","name":"Task","input":{"description":"разбор схемы"}}]}}""");
        assertThat(started).singleElement()
            .satisfies(e -> assertThat(e.kind()).isEqualTo(EventKind.SUBAGENT_START));

        List<AgentEvent> nested = parser.parseLine("""
            {"type":"assistant","session_id":"s1","parent_tool_use_id":"toolu_task",
             "message":{"content":[{"type":"text","text":"смотрю таблицы"}]}}""");
        assertThat(nested).singleElement()
            .satisfies(e -> assertThat(e.fromSubagent()).isTrue());

        List<AgentEvent> ended = parser.parseLine("""
            {"type":"user","session_id":"s1","message":{"content":[
              {"type":"tool_result","tool_use_id":"toolu_task","content":"готово","is_error":false}]}}""");
        assertThat(ended).singleElement()
            .satisfies(e -> assertThat(e.kind()).isEqualTo(EventKind.SUBAGENT_END));
    }

    @Test
    void malformedLineYieldsNoEventsAndDoesNotThrow() {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        assertThat(parser.parseLine("это не json")).isEmpty();
        assertThat(parser.parseLine("")).isEmpty();
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-agents -am test`
Expected: FAIL — `cannot find symbol: class ClaudeEventParser`.

- [ ] **Step 3: Написать парсер**

`aura-agents/src/main/java/aura/agents/ClaudeEventParser.java`:

```java
package aura.agents;

import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.EventKind;
import aura.core.ToolClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Превращает поток {@code claude -p --output-format stream-json} в канонические
 * события. Хранит состояние: строка результата инструмента не содержит его имени,
 * поэтому класс и цель берутся из запомненного вызова. Один экземпляр на сессию.
 */
public final class ClaudeEventParser {

    private static final Logger log = LoggerFactory.getLogger(ClaudeEventParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record PendingTool(ToolClass toolClass, String target, boolean subagent) {}

    private final Clock clock;
    private final Map<String, PendingTool> pending = new HashMap<>();
    private String sessionId = "unknown";

    public ClaudeEventParser(Clock clock) {
        this.clock = clock;
    }

    /** Возвращает от нуля до нескольких событий. Никогда не бросает. */
    public List<AgentEvent> parseLine(String jsonLine) {
        if (jsonLine == null || jsonLine.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(jsonLine);
        } catch (Exception e) {
            log.debug("нераспознанная строка потока claude: {}", abbreviate(jsonLine));
            return List.of();
        }

        String type = root.path("type").asText("");
        if (root.hasNonNull("session_id")) {
            sessionId = root.get("session_id").asText();
        }

        return switch (type) {
            case "system" -> parseSystem(root, jsonLine);
            case "assistant" -> parseAssistant(root, jsonLine);
            case "user" -> parseUser(root, jsonLine);
            case "result" -> List.of(parseResult(root, jsonLine));
            default -> List.of();
        };
    }

    private List<AgentEvent> parseSystem(JsonNode root, String raw) {
        String subtype = root.path("subtype").asText("");
        // Строки hook_started и hook_response — побочный шум от пользовательских
        // хуков. Они не относятся к работе агента и не должны попадать в поток.
        if (subtype.startsWith("hook")) {
            return List.of();
        }
        if ("init".equals(subtype)) {
            return List.of(base(root, raw)
                .kind(EventKind.SESSION_START)
                .target(root.path("cwd").asText(""))
                .build());
        }
        return List.of();
    }

    private List<AgentEvent> parseAssistant(JsonNode root, String raw) {
        List<AgentEvent> out = new ArrayList<>();
        for (JsonNode block : root.path("message").path("content")) {
            String blockType = block.path("type").asText("");
            if ("text".equals(blockType)) {
                String text = block.path("text").asText("");
                if (!text.isBlank()) {
                    out.add(base(root, raw)
                        .kind(EventKind.ASSISTANT_TEXT)
                        .toolClass(ToolClass.OTHER)
                        .target("")
                        .summaryHint(abbreviate(text))
                        .build());
                }
            } else if ("tool_use".equals(blockType)) {
                out.add(toolStart(root, raw, block));
            }
        }
        return out;
    }

    private AgentEvent toolStart(JsonNode root, String raw, JsonNode block) {
        String name = block.path("name").asText("");
        String id = block.path("id").asText("");
        ToolClass cls = ToolClass.of(name);
        String target = targetOf(name, block.path("input"));
        boolean subagent = cls == ToolClass.TASK;

        pending.put(id, new PendingTool(cls, target, subagent));

        return base(root, raw)
            .kind(subagent ? EventKind.SUBAGENT_START : EventKind.TOOL_START)
            .toolClass(cls)
            .target(target)
            .toolUseId(id)
            .summaryHint(target)
            .build();
    }

    private List<AgentEvent> parseUser(JsonNode root, String raw) {
        List<AgentEvent> out = new ArrayList<>();
        for (JsonNode block : root.path("message").path("content")) {
            if (!"tool_result".equals(block.path("type").asText(""))) {
                continue;
            }
            String id = block.path("tool_use_id").asText("");
            boolean isError = block.path("is_error").asBoolean(false);
            PendingTool tool = pending.remove(id);
            ToolClass cls = tool == null ? ToolClass.OTHER : tool.toolClass();
            String target = tool == null ? "" : tool.target();
            boolean subagent = tool != null && tool.subagent();

            EventKind kind = isError ? EventKind.ERROR
                : subagent ? EventKind.SUBAGENT_END
                : EventKind.TOOL_END;

            out.add(base(root, raw)
                .kind(kind)
                .toolClass(cls)
                .target(target)
                .toolUseId(id)
                .ok(!isError)
                .summaryHint(abbreviate(resultText(root, block)))
                .build());
        }
        return out;
    }

    private AgentEvent parseResult(JsonNode root, String raw) {
        boolean isError = root.path("is_error").asBoolean(false);
        return base(root, raw)
            .kind(EventKind.DONE)
            .toolClass(ToolClass.OTHER)
            .target("")
            .ok(!isError)
            .summaryHint(root.path("result").asText(""))
            .build();
    }

    /** Что именно произносить о вызове: команда, путь или шаблон. */
    private static String targetOf(String toolName, JsonNode input) {
        for (String field : new String[] {"command", "file_path", "path", "pattern", "url", "description"}) {
            if (input.hasNonNull(field)) {
                return input.get(field).asText();
            }
        }
        return toolName;
    }

    private static String resultText(JsonNode root, JsonNode block) {
        JsonNode stdout = root.path("tool_use_result").path("stdout");
        if (!stdout.isMissingNode() && !stdout.asText("").isBlank()) {
            return stdout.asText();
        }
        JsonNode content = block.path("content");
        return content.isTextual() ? content.asText() : content.toString();
    }

    private AgentEvent.Builder base(JsonNode root, String raw) {
        return AgentEvent.builder()
            .ts(timestampOf(root))
            .sessionId(sessionId)
            .agent(Agent.CLAUDE)
            .toolClass(ToolClass.OTHER)
            .target("")
            .parentToolUseId(root.hasNonNull("parent_tool_use_id")
                ? root.get("parent_tool_use_id").asText() : null)
            .raw(raw);
    }

    private Instant timestampOf(JsonNode root) {
        if (root.hasNonNull("timestamp")) {
            try {
                return Instant.parse(root.get("timestamp").asText());
            } catch (DateTimeParseException ignored) {
                // поток не обязан приносить время в каждой строке
            }
        }
        return clock.instant();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
```

- [ ] **Step 4: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-agents -am test`
Expected: PASS, восемь тестов.

Если путь до фикстуры не находится — Maven запускает тесты с рабочим каталогом модуля (`aura-agents`), поэтому `../testdata/...` верен. При запуске из IDE выставить рабочий каталог модуля.

- [ ] **Step 5: Закоммитить**

```bash
git add aura-agents/
git commit -m "feat(agents): normalize Claude Code stream-json into AgentEvent"
```

---

## Task 4: Адаптер Codex

**Files:**
- Create: `aura-agents/src/main/java/aura/agents/CodexEventParser.java`
- Test: `aura-agents/src/test/java/aura/agents/CodexEventParserTest.java`
- Читает: `testdata/fixtures/codex-exec-json-simple.jsonl` (менять нельзя)

**Interfaces:**
- Consumes: те же типы из Task 2
- Produces: `class CodexEventParser` с конструктором `CodexEventParser(Clock clock)` и методом `List<AgentEvent> parseLine(String jsonLine)`

- [ ] **Step 1: Написать падающий тест**

Создать `aura-agents/src/test/java/aura/agents/CodexEventParserTest.java`:

```java
package aura.agents;

import static org.assertj.core.api.Assertions.assertThat;

import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.EventKind;
import aura.core.ToolClass;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodexEventParserTest {

    private static final Path FIXTURE = Path.of("..", "testdata", "fixtures",
        "codex-exec-json-simple.jsonl");

    private static final Clock FIXED =
        Clock.fixed(Instant.parse("2026-08-26T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void mapsRecordedFixtureToSessionTextAndDone() throws Exception {
        CodexEventParser parser = new CodexEventParser(FIXED);
        List<AgentEvent> events = new ArrayList<>();
        for (String line : Files.readAllLines(FIXTURE, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                events.addAll(parser.parseLine(line));
            }
        }

        assertThat(events).extracting(AgentEvent::kind).containsExactly(
            EventKind.SESSION_START,
            EventKind.ASSISTANT_TEXT,
            EventKind.DONE);
        assertThat(events).allMatch(e -> e.agent() == Agent.CODEX);
        assertThat(events.get(0).sessionId()).isEqualTo("01a03b0d-4495-71a2-baf0-fb8a51dfddc5");
        assertThat(events.get(1).summaryHint()).isEqualTo("ok");
    }

    @Test
    void commandExecutionBecomesToolStartAndToolEnd() {
        CodexEventParser parser = new CodexEventParser(FIXED);
        parser.parseLine("""
            {"type":"thread.started","thread_id":"t1"}""");

        List<AgentEvent> started = parser.parseLine("""
            {"type":"item.started","item":{"id":"i1","type":"command_execution","command":"git status"}}""");
        assertThat(started).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.TOOL_START);
            assertThat(e.toolClass()).isEqualTo(ToolClass.EXEC);
            assertThat(e.target()).isEqualTo("git status");
        });

        // Команда намеренно не тест-раннер: прогон тестов станет отдельным видом
        // события в Task 5, и этот тест должен остаться про обычный вызов.
        List<AgentEvent> ended = parser.parseLine("""
            {"type":"item.completed","item":{"id":"i1","type":"command_execution",
             "command":"git status","exit_code":0,"aggregated_output":"nothing to commit"}}""");
        assertThat(ended).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.TOOL_END);
            assertThat(e.ok()).isTrue();
            assertThat(e.summaryHint()).contains("nothing to commit");
        });
    }

    @Test
    void nonZeroExitCodeBecomesError() {
        CodexEventParser parser = new CodexEventParser(FIXED);
        parser.parseLine("""
            {"type":"thread.started","thread_id":"t1"}""");
        List<AgentEvent> events = parser.parseLine("""
            {"type":"item.completed","item":{"id":"i9","type":"command_execution",
             "command":"pytest","exit_code":1,"aggregated_output":"1 failed"}}""");

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.ERROR);
            assertThat(e.ok()).isFalse();
        });
    }

    @Test
    void unknownItemTypeDegradesToOtherAndDoesNotBreakTheStream() {
        CodexEventParser parser = new CodexEventParser(FIXED);
        parser.parseLine("""
            {"type":"thread.started","thread_id":"t1"}""");

        List<AgentEvent> events = parser.parseLine("""
            {"type":"item.completed","item":{"id":"i2","type":"совершенно_новый_тип"}}""");

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.OTHER);
            assertThat(e.toolClass()).isEqualTo(ToolClass.OTHER);
        });
    }

    @Test
    void reasoningItemsAreSkippedEntirely() {
        CodexEventParser parser = new CodexEventParser(FIXED);
        parser.parseLine("""
            {"type":"thread.started","thread_id":"t1"}""");
        assertThat(parser.parseLine("""
            {"type":"item.completed","item":{"id":"i3","type":"reasoning","text":"думаю"}}"""))
            .isEmpty();
    }

    @Test
    void malformedLineYieldsNoEventsAndDoesNotThrow() {
        CodexEventParser parser = new CodexEventParser(FIXED);
        assertThat(parser.parseLine("{неполный")).isEmpty();
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-agents -am test`
Expected: FAIL — `cannot find symbol: class CodexEventParser`.

- [ ] **Step 3: Написать парсер**

`aura-agents/src/main/java/aura/agents/CodexEventParser.java`:

```java
package aura.agents;

import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.EventKind;
import aura.core.ToolClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Превращает поток {@code codex exec --json} в канонические события.
 *
 * <p>Полный перечень значений {@code item.type} на момент написания неизвестен
 * (см. RISK-2), поэтому схема расширяемая: незнакомый тип становится
 * {@link EventKind#OTHER} и не роняет поток.
 */
public final class CodexEventParser {

    private static final Logger log = LoggerFactory.getLogger(CodexEventParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Clock clock;
    private String sessionId = "unknown";

    public CodexEventParser(Clock clock) {
        this.clock = clock;
    }

    public List<AgentEvent> parseLine(String jsonLine) {
        if (jsonLine == null || jsonLine.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(jsonLine);
        } catch (Exception e) {
            log.debug("нераспознанная строка потока codex: {}", jsonLine);
            return List.of();
        }

        String type = root.path("type").asText("");
        return switch (type) {
            case "thread.started" -> {
                sessionId = root.path("thread_id").asText("unknown");
                yield List.of(base(jsonLine).kind(EventKind.SESSION_START).build());
            }
            case "turn.completed" -> List.of(base(jsonLine)
                .kind(EventKind.DONE)
                .ok(true)
                .build());
            case "item.started" -> item(root, jsonLine, true);
            case "item.completed" -> item(root, jsonLine, false);
            default -> List.of();
        };
    }

    private List<AgentEvent> item(JsonNode root, String raw, boolean started) {
        JsonNode item = root.path("item");
        String itemType = item.path("type").asText("");

        if ("reasoning".equals(itemType)) {
            return List.of();
        }
        if ("agent_message".equals(itemType)) {
            return started ? List.of() : List.of(base(raw)
                .kind(EventKind.ASSISTANT_TEXT)
                .summaryHint(item.path("text").asText(""))
                .build());
        }

        ToolClass cls = ToolClass.of(itemType);
        String target = targetOf(item);

        if (cls == ToolClass.OTHER) {
            return started ? List.of() : List.of(base(raw)
                .kind(EventKind.OTHER)
                .target(target)
                .build());
        }

        if (started) {
            return List.of(base(raw)
                .kind(EventKind.TOOL_START)
                .toolClass(cls)
                .target(target)
                .toolUseId(item.path("id").asText(""))
                .summaryHint(target)
                .build());
        }

        boolean ok = !item.hasNonNull("exit_code") || item.get("exit_code").asInt() == 0;
        return List.of(base(raw)
            .kind(ok ? EventKind.TOOL_END : EventKind.ERROR)
            .toolClass(cls)
            .target(target)
            .toolUseId(item.path("id").asText(""))
            .ok(ok)
            .summaryHint(item.path("aggregated_output").asText(""))
            .build());
    }

    private static String targetOf(JsonNode item) {
        for (String field : new String[] {"command", "path", "file_path", "query"}) {
            if (item.hasNonNull(field)) {
                return item.get(field).asText();
            }
        }
        return item.path("type").asText("");
    }

    private AgentEvent.Builder base(String raw) {
        return AgentEvent.builder()
            .ts(clock.instant())
            .sessionId(sessionId)
            .agent(Agent.CODEX)
            .toolClass(ToolClass.OTHER)
            .target("")
            .raw(raw);
    }
}
```

- [ ] **Step 4: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-agents -am test`
Expected: PASS, шесть тестов `CodexEventParserTest` плюс восемь из Task 3.

- [ ] **Step 5: Закоммитить**

```bash
git add aura-agents/
git commit -m "feat(agents): normalize Codex exec --json into AgentEvent"
```

---

## Task 5: Распознавание результата тестов

**Files:**
- Create: `aura-agents/src/main/java/aura/agents/TestResultDetector.java`
- Test: `aura-agents/src/test/java/aura/agents/TestResultDetectorTest.java`

**Interfaces:**
- Consumes: `ToolClass.EXEC` из Task 2
- Produces:
  - `record TestResultDetector.Outcome(int passed, int failed, boolean ok)`
  - `static Optional<Outcome> TestResultDetector.detect(String command, String output)`

Зачем отдельная задача: результат тестов — единственный триггер нарратора, который нельзя вывести из типа события. Он выводится из содержимого, значит это эвристика, а эвристика должна быть заперта в одном месте и покрыта таблицей.

- [ ] **Step 1: Написать падающий тест**

Создать `aura-agents/src/test/java/aura/agents/TestResultDetectorTest.java`:

```java
package aura.agents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestResultDetectorTest {

    @Test
    void detectsPytestGreen() {
        var outcome = TestResultDetector.detect("pytest -q tests/", "4 passed in 0.31s").orElseThrow();
        assertThat(outcome.passed()).isEqualTo(4);
        assertThat(outcome.failed()).isZero();
        assertThat(outcome.ok()).isTrue();
    }

    @Test
    void detectsPytestRed() {
        var outcome = TestResultDetector
            .detect("pytest tests/test_auth.py", "3 passed, 1 failed in 1.02s").orElseThrow();
        assertThat(outcome.passed()).isEqualTo(3);
        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(outcome.ok()).isFalse();
    }

    @Test
    void detectsMavenSurefireSummary() {
        var outcome = TestResultDetector.detect("mvn -q test",
            "Tests run: 12, Failures: 2, Errors: 1, Skipped: 0").orElseThrow();
        assertThat(outcome.passed()).isEqualTo(9);
        assertThat(outcome.failed()).isEqualTo(3);
        assertThat(outcome.ok()).isFalse();
    }

    @Test
    void detectsJestSummary() {
        var outcome = TestResultDetector.detect("npm test",
            "Tests:       2 failed, 18 passed, 20 total").orElseThrow();
        assertThat(outcome.passed()).isEqualTo(18);
        assertThat(outcome.failed()).isEqualTo(2);
        assertThat(outcome.ok()).isFalse();
    }

    @Test
    void ignoresCommandsThatAreNotTestRunners() {
        assertThat(TestResultDetector.detect("git status", "4 passed")).isEmpty();
        assertThat(TestResultDetector.detect("echo 12 passed", "12 passed")).isEmpty();
    }

    @Test
    void testRunnerWithUnrecognisedOutputYieldsNothing() {
        assertThat(TestResultDetector.detect("pytest", "collecting ...")).isEmpty();
    }

    @Test
    void handlesNullsWithoutThrowing() {
        assertThat(TestResultDetector.detect(null, null)).isEmpty();
        assertThat(TestResultDetector.detect("pytest", null)).isEmpty();
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-agents -am test`
Expected: FAIL — `cannot find symbol: class TestResultDetector`.

- [ ] **Step 3: Написать эвристику**

`aura-agents/src/main/java/aura/agents/TestResultDetector.java`:

```java
package aura.agents;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Выводит результат прогона тестов из команды и её вывода.
 *
 * <p>Два условия должны выполниться одновременно: команда похожа на тест-раннер
 * и вывод похож на сводку. Одного вывода недостаточно — иначе {@code echo "4 passed"}
 * превратится в бодрый доклад о зелёных тестах.
 */
public final class TestResultDetector {

    public record Outcome(int passed, int failed, boolean ok) {}

    private static final List<Pattern> RUNNERS = List.of(
        Pattern.compile("(^|[\\\\/\\s])pytest\\b"),
        Pattern.compile("\\bmvn\\b.*\\b(test|verify)\\b"),
        Pattern.compile("\\bgradlew?\\b.*\\btest\\b"),
        Pattern.compile("\\bnpm\\b\\s+(run\\s+)?test\\b"),
        Pattern.compile("\\b(jest|vitest)\\b"),
        Pattern.compile("\\bgo\\s+test\\b"),
        Pattern.compile("\\bcargo\\s+test\\b")
    );

    private static final Pattern PYTEST =
        Pattern.compile("(?:(\\d+)\\s+passed)?(?:,?\\s*(\\d+)\\s+failed)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUREFIRE = Pattern.compile(
        "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JEST = Pattern.compile(
        "Tests:\\s*(?:(\\d+)\\s+failed,\\s*)?(\\d+)\\s+passed", Pattern.CASE_INSENSITIVE);

    private TestResultDetector() {
    }

    public static Optional<Outcome> detect(String command, String output) {
        if (command == null || output == null || output.isBlank()) {
            return Optional.empty();
        }
        String cmd = command.toLowerCase(Locale.ROOT);
        boolean isRunner = RUNNERS.stream().anyMatch(p -> p.matcher(cmd).find());
        if (!isRunner) {
            return Optional.empty();
        }

        Matcher surefire = SUREFIRE.matcher(output);
        if (surefire.find()) {
            int run = Integer.parseInt(surefire.group(1));
            int failed = Integer.parseInt(surefire.group(2)) + Integer.parseInt(surefire.group(3));
            return Optional.of(new Outcome(run - failed, failed, failed == 0));
        }

        Matcher jest = JEST.matcher(output);
        if (jest.find()) {
            int failed = jest.group(1) == null ? 0 : Integer.parseInt(jest.group(1));
            int passed = Integer.parseInt(jest.group(2));
            return Optional.of(new Outcome(passed, failed, failed == 0));
        }

        Matcher pytest = PYTEST.matcher(output);
        while (pytest.find()) {
            String passedGroup = pytest.group(1);
            String failedGroup = pytest.group(2);
            if (passedGroup == null && failedGroup == null) {
                continue;
            }
            int passed = passedGroup == null ? 0 : Integer.parseInt(passedGroup);
            int failed = failedGroup == null ? 0 : Integer.parseInt(failedGroup);
            return Optional.of(new Outcome(passed, failed, failed == 0));
        }

        return Optional.empty();
    }
}
```

- [ ] **Step 4: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-agents -am test`
Expected: PASS, семь тестов `TestResultDetectorTest`.

- [ ] **Step 5: Написать падающий тест на подключение эвристики к обоим адаптерам**

Детектор бесполезен, пока его никто не зовёт. Событие `TEST_RESULT` — главный
триггер будущего нарратора, поэтому оно должно рождаться в адаптерах, а не
где-то выше.

Дописать в `aura-agents/src/test/java/aura/agents/ClaudeEventParserTest.java`:

```java
    @Test
    void successfulTestRunBecomesTestResultRatherThanPlainToolEnd() {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        parser.parseLine("""
            {"type":"system","subtype":"init","session_id":"s1","cwd":"C:/x"}""");
        parser.parseLine("""
            {"type":"assistant","session_id":"s1","message":{"content":[
              {"type":"tool_use","id":"toolu_t","name":"Bash","input":{"command":"pytest -q"}}]}}""");

        List<AgentEvent> events = parser.parseLine("""
            {"type":"user","session_id":"s1","message":{"content":[
              {"type":"tool_result","tool_use_id":"toolu_t","content":"4 passed in 0.3s","is_error":false}]},
             "tool_use_result":{"stdout":"4 passed in 0.3s","stderr":""}}""");

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.TEST_RESULT);
            assertThat(e.ok()).isTrue();
            assertThat(e.summaryHint()).contains("4").contains("passed");
        });
    }

    @Test
    void failingTestRunIsTestResultWithOkFalse() {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        parser.parseLine("""
            {"type":"system","subtype":"init","session_id":"s1","cwd":"C:/x"}""");
        parser.parseLine("""
            {"type":"assistant","session_id":"s1","message":{"content":[
              {"type":"tool_use","id":"toolu_t","name":"Bash","input":{"command":"pytest"}}]}}""");

        List<AgentEvent> events = parser.parseLine("""
            {"type":"user","session_id":"s1","message":{"content":[
              {"type":"tool_result","tool_use_id":"toolu_t","content":"3 passed, 1 failed","is_error":false}]},
             "tool_use_result":{"stdout":"3 passed, 1 failed","stderr":""}}""");

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.TEST_RESULT);
            assertThat(e.ok()).isFalse();
        });
    }

    @Test
    void ordinaryCommandStaysToolEnd() {
        ClaudeEventParser parser = new ClaudeEventParser(FIXED);
        parser.parseLine("""
            {"type":"system","subtype":"init","session_id":"s1","cwd":"C:/x"}""");
        parser.parseLine("""
            {"type":"assistant","session_id":"s1","message":{"content":[
              {"type":"tool_use","id":"toolu_g","name":"Bash","input":{"command":"git status"}}]}}""");

        List<AgentEvent> events = parser.parseLine("""
            {"type":"user","session_id":"s1","message":{"content":[
              {"type":"tool_result","tool_use_id":"toolu_g","content":"clean","is_error":false}]}}""");

        assertThat(events).singleElement()
            .satisfies(e -> assertThat(e.kind()).isEqualTo(EventKind.TOOL_END));
    }
```

Дописать в `aura-agents/src/test/java/aura/agents/CodexEventParserTest.java`:

```java
    @Test
    void testRunnerCommandBecomesTestResult() {
        CodexEventParser parser = new CodexEventParser(FIXED);
        parser.parseLine("""
            {"type":"thread.started","thread_id":"t1"}""");

        List<AgentEvent> events = parser.parseLine("""
            {"type":"item.completed","item":{"id":"i1","type":"command_execution",
             "command":"pytest -q","exit_code":0,"aggregated_output":"4 passed in 0.2s"}}""");

        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(EventKind.TEST_RESULT);
            assertThat(e.ok()).isTrue();
        });
    }
```

- [ ] **Step 6: Подключить эвристику в `ClaudeEventParser`**

В `parseUser` заменить вычисление `kind` и `summaryHint` так, чтобы прогон тестов
опознавался отдельным видом события:

```java
            EventKind kind = isError ? EventKind.ERROR
                : subagent ? EventKind.SUBAGENT_END
                : EventKind.TOOL_END;

            String output = resultText(root, block);
            String hint = abbreviate(output);
            Boolean ok = !isError;

            // Результат тестов — отдельный вид события: это главная новость в
            // работе агента, и нарратор обязан отличать её от обычной команды.
            if (kind == EventKind.TOOL_END && cls == ToolClass.EXEC) {
                var outcome = TestResultDetector.detect(target, output);
                if (outcome.isPresent()) {
                    kind = EventKind.TEST_RESULT;
                    ok = outcome.get().ok();
                    hint = outcome.get().passed() + " passed, " + outcome.get().failed() + " failed";
                }
            }

            out.add(base(root, raw)
                .kind(kind)
                .toolClass(cls)
                .target(target)
                .toolUseId(id)
                .ok(ok)
                .summaryHint(hint)
                .build());
```

Переменные `kind`, `ok` и `hint` объявлены как обычные локальные, не `final`.

- [ ] **Step 7: Подключить эвристику в `CodexEventParser`**

В методе `item`, в ветке завершения, перед сборкой события:

```java
        boolean ok = !item.hasNonNull("exit_code") || item.get("exit_code").asInt() == 0;
        String output = item.path("aggregated_output").asText("");
        EventKind kind = ok ? EventKind.TOOL_END : EventKind.ERROR;
        String hint = output;

        if (kind == EventKind.TOOL_END && cls == ToolClass.EXEC) {
            var outcome = TestResultDetector.detect(target, output);
            if (outcome.isPresent()) {
                kind = EventKind.TEST_RESULT;
                ok = outcome.get().ok();
                hint = outcome.get().passed() + " passed, " + outcome.get().failed() + " failed";
            }
        }

        return List.of(base(raw)
            .kind(kind)
            .toolClass(cls)
            .target(target)
            .toolUseId(item.path("id").asText(""))
            .ok(ok)
            .summaryHint(hint)
            .build());
```

- [ ] **Step 8: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-agents -am test`
Expected: PASS — все тесты обоих адаптеров плюс три новых на `TEST_RESULT`.

- [ ] **Step 9: Закоммитить**

```bash
git add aura-agents/
git commit -m "feat(agents): raise test outcomes as their own event kind"
```

---

## Task 6: Реестр проектов

**Files:**
- Create: `aura-core/src/main/java/aura/core/Project.java`
- Create: `aura-core/src/main/java/aura/core/ProjectRegistry.java`
- Create: `aura-app/src/main/java/aura/app/ProjectRegistryLoader.java`
- Test: `aura-core/src/test/java/aura/core/ProjectRegistryTest.java`
- Test: `aura-app/src/test/java/aura/app/ProjectRegistryLoaderTest.java`

**Interfaces:**
- Consumes: `Agent` из Task 2
- Produces:
  - `record Project(String name, List<String> aliases, Path path, Agent agent, List<Path> addDirs, Set<String> allow, Set<String> confirm, Set<String> deny)`
  - `class ProjectRegistry` с `ProjectRegistry(List<Project>)`, `Optional<Project> byName(String)`, `Optional<Project> resolveFromSpeech(String phrase)`, `List<Project> all()`
  - `class ProjectRegistryLoader` со статическим `ProjectRegistry load(Path yamlFile)`

Разделение намеренное: сопоставление фразы с проектом — доменная логика и живёт в `aura-core` без зависимостей; чтение YAML — ввод-вывод и живёт в `aura-app`.

- [ ] **Step 1: Написать падающий тест на сопоставление**

Создать `aura-core/src/test/java/aura/core/ProjectRegistryTest.java`:

```java
package aura.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectRegistryTest {

    private static Project project(String name, String... aliases) {
        return new Project(name, List.of(aliases), Path.of("C:", "work", name),
            Agent.CLAUDE, List.of(),
            Set.of("Read", "Grep", "Glob", "Edit"),
            Set.of("Bash", "Write"),
            Set.of("WebSearch"));
    }

    private final ProjectRegistry registry = new ProjectRegistry(List.of(
        project("backend", "бэкенд", "сервер"),
        project("frontend", "фронтенд", "морда")));

    @Test
    void findsByExactName() {
        assertThat(registry.byName("backend")).map(Project::name).contains("backend");
        assertThat(registry.byName("нет такого")).isEmpty();
    }

    @Test
    void findsByAliasInsideSpokenPhrase() {
        assertThat(registry.resolveFromSpeech("в проекте бэкенд почини тесты"))
            .map(Project::name).contains("backend");
        assertThat(registry.resolveFromSpeech("подними фронтенд и проверь сборку"))
            .map(Project::name).contains("frontend");
    }

    @Test
    void matchingIsCaseAndPunctuationInsensitive() {
        assertThat(registry.resolveFromSpeech("В ПРОЕКТЕ, Бэкенд: почини тесты!"))
            .map(Project::name).contains("backend");
    }

    @Test
    void toleratesOneRecognitionErrorInTheAlias() {
        // Распознавание регулярно роняет одну букву; проект от этого теряться не должен.
        assertThat(registry.resolveFromSpeech("в проекте бэкент почини тесты"))
            .map(Project::name).contains("backend");
    }

    @Test
    void returnsEmptyWhenNoProjectIsNamed() {
        assertThat(registry.resolveFromSpeech("почини падающие тесты")).isEmpty();
    }

    @Test
    void ambiguousPhraseWithTwoProjectsResolvesToNothing() {
        // Два названных проекта — это не повод угадывать. Пусть спросит.
        assertThat(registry.resolveFromSpeech("перенеси из бэкенд во фронтенд")).isEmpty();
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-core test`
Expected: FAIL — `cannot find symbol: class Project`.

- [ ] **Step 3: Написать `Project` и `ProjectRegistry`**

`aura-core/src/main/java/aura/core/Project.java`:

```java
package aura.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Проект из реестра. Агент запускается только в каталоге проекта и в его
 * {@code addDirs}; путь вне реестра не запускается никогда.
 */
public record Project(
    String name,
    List<String> aliases,
    Path path,
    Agent agent,
    List<Path> addDirs,
    Set<String> allow,
    Set<String> confirm,
    Set<String> deny
) {
    public Project {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(agent, "agent");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        addDirs = addDirs == null ? List.of() : List.copyOf(addDirs);
        allow = allow == null ? Set.of() : Set.copyOf(allow);
        confirm = confirm == null ? Set.of() : Set.copyOf(confirm);
        deny = deny == null ? Set.of() : Set.copyOf(deny);
    }

    /** Все слова, по которым проект можно назвать вслух: имя плюс псевдонимы. */
    public List<String> spokenForms() {
        List<String> forms = new java.util.ArrayList<>(aliases.size() + 1);
        forms.add(name);
        forms.addAll(aliases);
        return List.copyOf(forms);
    }
}
```

`aura-core/src/main/java/aura/core/ProjectRegistry.java`:

```java
package aura.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Поиск проекта по имени и по произнесённой фразе.
 *
 * <p>Сопоставление намеренно строгое в одном месте и терпимое в другом: одна
 * ошибка распознавания в слове прощается, а вот две названные цели — нет.
 * Угадывать проект, в котором агент получит право писать файлы, нельзя.
 */
public final class ProjectRegistry {

    private static final int MAX_EDIT_DISTANCE = 1;

    private final List<Project> projects;

    public ProjectRegistry(List<Project> projects) {
        this.projects = List.copyOf(projects);
    }

    public List<Project> all() {
        return projects;
    }

    public Optional<Project> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = name.toLowerCase(Locale.ROOT);
        return projects.stream()
            .filter(p -> p.name().toLowerCase(Locale.ROOT).equals(needle))
            .findFirst();
    }

    /** Пустой результат означает «спроси у пользователя», а не «возьми любой». */
    public Optional<Project> resolveFromSpeech(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return Optional.empty();
        }
        List<String> words = List.of(normalize(phrase).split("\\s+"));
        Set<Project> hits = new LinkedHashSet<>();

        for (Project project : projects) {
            for (String form : project.spokenForms()) {
                String target = normalize(form);
                boolean matched = words.stream().anyMatch(w ->
                    w.equals(target) || editDistanceWithin(w, target, MAX_EDIT_DISTANCE));
                if (matched) {
                    hits.add(project);
                    break;
                }
            }
        }

        return hits.size() == 1 ? Optional.of(hits.iterator().next()) : Optional.empty();
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static boolean editDistanceWithin(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) {
            return false;
        }
        // Короткие слова не прощают опечаток: иначе «дом» совпадёт с «том».
        if (b.length() < 4) {
            return false;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()] <= max;
    }
}
```

- [ ] **Step 4: Запустить тест реестра и убедиться, что проходит**

Run: `mvn -q -pl aura-core test`
Expected: PASS, шесть тестов `ProjectRegistryTest`.

- [ ] **Step 5: Написать падающий тест на загрузчик YAML**

Создать `aura-app/src/test/java/aura/app/ProjectRegistryLoaderTest.java`:

```java
package aura.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aura.core.Agent;
import aura.core.Project;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectRegistryLoaderTest {

    @Test
    void loadsProjectsWithClassesAndDefaults(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("projects.yaml");
        Files.writeString(yaml, """
            projects:
              - name: backend
                aliases: ["бэкенд", "сервер"]
                path: C:\\work\\backend
                agent: claude
                allow: ["Read", "Grep"]
                confirm: ["Bash"]
                deny: ["WebSearch"]
              - name: scratch
                path: C:\\work\\scratch
                agent: codex
            """, StandardCharsets.UTF_8);

        var registry = ProjectRegistryLoader.load(yaml);

        assertThat(registry.all()).hasSize(2);
        Project backend = registry.byName("backend").orElseThrow();
        assertThat(backend.aliases()).containsExactly("бэкенд", "сервер");
        assertThat(backend.agent()).isEqualTo(Agent.CLAUDE);
        assertThat(backend.confirm()).containsExactly("Bash");

        Project scratch = registry.byName("scratch").orElseThrow();
        assertThat(scratch.agent()).isEqualTo(Agent.CODEX);
        // Умолчание безопасное: ничего не разрешено, значит всё спросят.
        assertThat(scratch.allow()).isEmpty();
        assertThat(scratch.aliases()).isEmpty();
    }

    @Test
    void missingFileYieldsEmptyRegistryRatherThanCrash(@TempDir Path tmp) {
        var registry = ProjectRegistryLoader.load(tmp.resolve("нет-такого.yaml"));
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void projectWithoutPathIsRejectedLoudly(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("projects.yaml");
        Files.writeString(yaml, """
            projects:
              - name: broken
                agent: claude
            """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ProjectRegistryLoader.load(yaml))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("broken");
    }
}
```

- [ ] **Step 6: Написать загрузчик**

`aura-app/src/main/java/aura/app/ProjectRegistryLoader.java`:

```java
package aura.app;

import aura.core.Agent;
import aura.core.Project;
import aura.core.ProjectRegistry;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/** Читает {@code projects.yaml} в доменный реестр. Единственное место, знающее про YAML. */
public final class ProjectRegistryLoader {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistryLoader.class);

    private ProjectRegistryLoader() {
    }

    public static ProjectRegistry load(Path yamlFile) {
        if (!Files.isRegularFile(yamlFile)) {
            log.warn("реестр проектов не найден: {} — работаем с пустым списком", yamlFile);
            return new ProjectRegistry(List.of());
        }
        try (InputStream in = Files.newInputStream(yamlFile)) {
            Map<String, Object> root = new Yaml().load(in);
            List<Project> projects = new ArrayList<>();
            Object raw = root == null ? null : root.get("projects");
            if (raw instanceof List<?> list) {
                for (Object element : list) {
                    if (element instanceof Map<?, ?> map) {
                        projects.add(toProject(map));
                    }
                }
            }
            return new ProjectRegistry(projects);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("не удалось прочитать реестр проектов: " + yamlFile, e);
        }
    }

    private static Project toProject(Map<?, ?> map) {
        String name = str(map.get("name"), "<без имени>");
        Object path = map.get("path");
        if (path == null) {
            throw new IllegalArgumentException("у проекта " + name + " не задан path");
        }
        return new Project(
            name,
            strings(map.get("aliases")).stream().toList(),
            Path.of(String.valueOf(path)),
            "codex".equals(str(map.get("agent"), "claude").toLowerCase(Locale.ROOT))
                ? Agent.CODEX : Agent.CLAUDE,
            strings(map.get("addDirs")).stream().map(Path::of).toList(),
            strings(map.get("allow")),
            strings(map.get("confirm")),
            strings(map.get("deny")));
    }

    private static Set<String> strings(Object value) {
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
```

- [ ] **Step 7: Запустить тесты и убедиться, что проходят**

Run: `mvn -q test`
Expected: PASS во всех модулях.

- [ ] **Step 8: Закоммитить**

```bash
git add aura-core/ aura-app/
git commit -m "feat(core): resolve projects by name and by spoken phrase"
```

---

## Task 7: Политика разрешений

**Files:**
- Create: `aura-policy/src/main/java/aura/policy/Decision.java`
- Create: `aura-policy/src/main/java/aura/policy/ToolRequest.java`
- Create: `aura-policy/src/main/java/aura/policy/PermissionPolicy.java`
- Test: `aura-policy/src/test/java/aura/policy/PermissionPolicyTest.java`

**Interfaces:**
- Consumes: `Project` из Task 6
- Produces:
  - `enum Decision { ALLOW, CONFIRM, DENY }`
  - `record ToolRequest(String toolName, String toolInputJson, Path cwd)`
  - `class PermissionPolicy` с `PermissionPolicy(Project project)` и `Decision decide(ToolRequest request)`

- [ ] **Step 1: Написать падающий тест**

Создать `aura-policy/src/test/java/aura/policy/PermissionPolicyTest.java`:

```java
package aura.policy;

import static org.assertj.core.api.Assertions.assertThat;

import aura.core.Agent;
import aura.core.Project;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PermissionPolicyTest {

    private static final Path ROOT = Path.of("C:", "work", "backend");
    private static final Path EXTRA = Path.of("C:", "work", "shared");

    private final Project project = new Project(
        "backend", List.of("бэкенд"), ROOT, Agent.CLAUDE, List.of(EXTRA),
        Set.of("Read", "Grep", "Glob", "Edit"),
        Set.of("Bash", "Write"),
        Set.of("WebSearch"));

    private final PermissionPolicy policy = new PermissionPolicy(project);

    private static ToolRequest req(String tool, String inputJson) {
        return new ToolRequest(tool, inputJson, ROOT);
    }

    @Test
    void deniedToolIsAlwaysDeniedEvenIfAlsoListedElsewhere() {
        assertThat(policy.decide(req("WebSearch", "{}"))).isEqualTo(Decision.DENY);
    }

    @Test
    void allowedReadOnlyToolPasses() {
        assertThat(policy.decide(req("Grep", "{\"pattern\":\"TODO\"}"))).isEqualTo(Decision.ALLOW);
        assertThat(policy.decide(req("Read", "{\"file_path\":\"C:\\\\work\\\\backend\\\\a.java\"}")))
            .isEqualTo(Decision.ALLOW);
    }

    @Test
    void editInsideProjectIsAllowed() {
        assertThat(policy.decide(req("Edit", "{\"file_path\":\"C:\\\\work\\\\backend\\\\src\\\\A.java\"}")))
            .isEqualTo(Decision.ALLOW);
    }

    @Test
    void editInsideAdditionalDirectoryIsAllowed() {
        assertThat(policy.decide(req("Edit", "{\"file_path\":\"C:\\\\work\\\\shared\\\\B.java\"}")))
            .isEqualTo(Decision.ALLOW);
    }

    @Test
    void editOutsideEveryAllowedDirectoryEscalatesToConfirm() {
        // Разрешение на Edit выдано проекту, а не всей файловой системе.
        assertThat(policy.decide(req("Edit", "{\"file_path\":\"C:\\\\Windows\\\\System32\\\\drivers\\\\etc\\\\hosts\"}")))
            .isEqualTo(Decision.CONFIRM);
    }

    @Test
    void confirmClassAlwaysAsks() {
        assertThat(policy.decide(req("Bash", "{\"command\":\"rm -rf build\"}"))).isEqualTo(Decision.CONFIRM);
    }

    @Test
    void unlistedToolAsksRatherThanAllows() {
        assertThat(policy.decide(req("СовершенноНовыйИнструмент", "{}"))).isEqualTo(Decision.CONFIRM);
    }

    @Test
    void malformedInputJsonEscalatesToConfirm() {
        // Не разобрали ввод — значит не знаем, куда пишут. Спрашиваем.
        assertThat(policy.decide(req("Edit", "{это не json"))).isEqualTo(Decision.CONFIRM);
    }

    @Test
    void relativePathIsResolvedAgainstWorkingDirectory() {
        assertThat(policy.decide(new ToolRequest("Edit", "{\"file_path\":\"src/A.java\"}", ROOT)))
            .isEqualTo(Decision.ALLOW);
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-policy -am test`
Expected: FAIL — `cannot find symbol: class PermissionPolicy`.

- [ ] **Step 3: Написать типы и политику**

`aura-policy/src/main/java/aura/policy/Decision.java`:

```java
package aura.policy;

/** Вердикт по вызову инструмента. {@link #CONFIRM} означает «спросить пользователя». */
public enum Decision {
    ALLOW,
    CONFIRM,
    DENY
}
```

`aura-policy/src/main/java/aura/policy/ToolRequest.java`:

```java
package aura.policy;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Запрос инструмента, как его видит хук: имя, необработанный JSON аргументов и
 * рабочий каталог агента.
 */
public record ToolRequest(String toolName, String toolInputJson, Path cwd) {
    public ToolRequest {
        Objects.requireNonNull(toolName, "toolName");
        toolInputJson = toolInputJson == null ? "{}" : toolInputJson;
        Objects.requireNonNull(cwd, "cwd");
    }
}
```

`aura-policy/src/main/java/aura/policy/PermissionPolicy.java`:

```java
package aura.policy;

import aura.core.Project;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Классифицирует вызов инструмента по реестру проекта.
 *
 * <p>Порядок проверок задан безопасностью, а не удобством: сначала запрет, затем
 * пишущие инструменты с проверкой пути, затем явное разрешение, и только в конце
 * — умолчание. Умолчание — {@link Decision#CONFIRM}: инструмент, о котором
 * политика ничего не знает, не исполняется молча.
 */
public final class PermissionPolicy {

    private static final Logger log = LoggerFactory.getLogger(PermissionPolicy.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Инструменты, которые пишут: для них важен не только класс, но и путь. */
    private static final Set<String> WRITING_TOOLS =
        Set.of("edit", "write", "multiedit", "notebookedit");

    private static final List<String> PATH_FIELDS = List.of("file_path", "path", "notebook_path");

    private final Project project;

    public PermissionPolicy(Project project) {
        this.project = project;
    }

    public Decision decide(ToolRequest request) {
        String tool = request.toolName();

        if (contains(project.deny(), tool)) {
            return Decision.DENY;
        }

        if (WRITING_TOOLS.contains(tool.toLowerCase(java.util.Locale.ROOT))) {
            if (!contains(project.allow(), tool)) {
                return Decision.CONFIRM;
            }
            return writesInsideAllowedRoots(request) ? Decision.ALLOW : Decision.CONFIRM;
        }

        if (contains(project.allow(), tool)) {
            return Decision.ALLOW;
        }

        if (contains(project.confirm(), tool)) {
            return Decision.CONFIRM;
        }

        return Decision.CONFIRM;
    }

    private boolean writesInsideAllowedRoots(ToolRequest request) {
        JsonNode input;
        try {
            input = MAPPER.readTree(request.toolInputJson());
        } catch (Exception e) {
            log.debug("не разобрали аргументы {} — эскалация до подтверждения", request.toolName());
            return false;
        }

        for (String field : PATH_FIELDS) {
            if (input.hasNonNull(field)) {
                Path target = Path.of(input.get(field).asText());
                Path resolved = (target.isAbsolute() ? target : request.cwd().resolve(target))
                    .normalize();
                if (isUnder(resolved, project.path())) {
                    return true;
                }
                for (Path extra : project.addDirs()) {
                    if (isUnder(resolved, extra)) {
                        return true;
                    }
                }
                return false;
            }
        }
        // Пишущий инструмент без пути в аргументах — непрозрачный случай, спрашиваем.
        return false;
    }

    private static boolean isUnder(Path candidate, Path root) {
        return candidate.normalize().startsWith(root.normalize());
    }

    private static boolean contains(Set<String> set, String tool) {
        return set.stream().anyMatch(t -> t.equalsIgnoreCase(tool));
    }
}
```

- [ ] **Step 4: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-policy -am test`
Expected: PASS, девять тестов.

- [ ] **Step 5: Закоммитить**

```bash
git add aura-policy/
git commit -m "feat(policy): classify tool calls as allow, confirm or deny"
```

---

## Task 8: Подтверждение и отказ по умолчанию

**Files:**
- Create: `aura-policy/src/main/java/aura/policy/ConfirmationProvider.java`
- Create: `aura-policy/src/main/java/aura/policy/TrayConfirmationProvider.java`
- Test: `aura-policy/src/test/java/aura/policy/ConfirmationProviderTest.java`

**Interfaces:**
- Consumes: `Decision`, `ToolRequest` из Task 7
- Produces:
  - `interface ConfirmationProvider { Decision confirm(ToolRequest request, Duration timeout); }` со статическими `ConfirmationProvider.guarded(ConfirmationProvider delegate)` и `ConfirmationProvider.guarded(ConfirmationProvider delegate, Duration grace)`
  - `class TrayConfirmationProvider implements ConfirmationProvider`

Смысл `guarded`: обёртка, превращающая таймаут, исключение и любой ответ кроме `ALLOW` в `DENY`. Голосовая реализация из M4 подключится к тому же интерфейсу и получит ту же защиту бесплатно.

- [ ] **Step 1: Написать падающий тест**

Создать `aura-policy/src/test/java/aura/policy/ConfirmationProviderTest.java`:

```java
package aura.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ConfirmationProviderTest {

    private static final ToolRequest REQUEST =
        new ToolRequest("Bash", "{\"command\":\"rm -rf build\"}", Path.of("C:", "work"));

    @Test
    void explicitAllowPassesThrough() {
        ConfirmationProvider guarded = ConfirmationProvider.guarded((r, t) -> Decision.ALLOW);
        assertThat(guarded.confirm(REQUEST, Duration.ofSeconds(1))).isEqualTo(Decision.ALLOW);
    }

    @Test
    void explicitDenyStaysDeny() {
        ConfirmationProvider guarded = ConfirmationProvider.guarded((r, t) -> Decision.DENY);
        assertThat(guarded.confirm(REQUEST, Duration.ofSeconds(1))).isEqualTo(Decision.DENY);
    }

    @Test
    void confirmAsAnAnswerIsTreatedAsDeny() {
        // CONFIRM — это вопрос, а не ответ. Реализация, вернувшая его, ошиблась.
        ConfirmationProvider guarded = ConfirmationProvider.guarded((r, t) -> Decision.CONFIRM);
        assertThat(guarded.confirm(REQUEST, Duration.ofSeconds(1))).isEqualTo(Decision.DENY);
    }

    @Test
    void nullAnswerIsTreatedAsDeny() {
        ConfirmationProvider guarded = ConfirmationProvider.guarded((r, t) -> null);
        assertThat(guarded.confirm(REQUEST, Duration.ofSeconds(1))).isEqualTo(Decision.DENY);
    }

    @Test
    void thrownExceptionIsTreatedAsDeny() {
        ConfirmationProvider guarded = ConfirmationProvider.guarded((r, t) -> {
            throw new IllegalStateException("диалог не открылся");
        });
        assertThat(guarded.confirm(REQUEST, Duration.ofSeconds(1))).isEqualTo(Decision.DENY);
    }

    @Test
    void slowAnswerIsCutOffByTimeoutAndBecomesDeny() {
        AtomicBoolean finished = new AtomicBoolean(false);
        // Запас нулевой: здесь проверяется сам обрыв, а не поведение диалога,
        // который закрывает себя сам.
        ConfirmationProvider guarded = ConfirmationProvider.guarded((r, t) -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Decision.DENY;
            }
            finished.set(true);
            return Decision.ALLOW;
        }, Duration.ZERO);

        long started = System.nanoTime();
        Decision decision = guarded.confirm(REQUEST, Duration.ofMillis(200));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(decision).isEqualTo(Decision.DENY);
        assertThat(elapsedMs).isLessThan(1500);
        assertThat(finished).isFalse();
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-policy -am test`
Expected: FAIL — `cannot find symbol: interface ConfirmationProvider`.

- [ ] **Step 3: Написать интерфейс с защитой**

`aura-policy/src/main/java/aura/policy/ConfirmationProvider.java`:

```java
package aura.policy;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Спрашивает у пользователя, исполнять ли опасный вызов.
 *
 * <p>В M1 реализация одна — модальный диалог из трея. В M4 к тому же интерфейсу
 * подключится голосовая, и диалог останется запасным путём.
 */
@FunctionalInterface
public interface ConfirmationProvider {

    /** Возвращает только {@link Decision#ALLOW} или {@link Decision#DENY}. */
    Decision confirm(ToolRequest request, Duration timeout);

    /**
     * Оборачивает реализацию так, что таймаут, исключение и любой ответ, кроме
     * явного {@link Decision#ALLOW}, становятся отказом. Отказ по умолчанию —
     * не деталь реализации, а требование безопасности, поэтому он здесь, а не
     * в каждой реализации по отдельности.
     */
    static ConfirmationProvider guarded(ConfirmationProvider delegate) {
        return guarded(delegate, Duration.ofSeconds(2));
    }

    /**
     * То же самое с явным запасом поверх тайм-аута. Запас существует ради
     * реализаций, которые закрывают себя сами (диалог гасит окно ровно на
     * тайм-ауте): без запаса обёртка и реализация гонялись бы за одну и ту же
     * миллисекунду. Нулевой запас означает жёсткий обрыв.
     */
    static ConfirmationProvider guarded(ConfirmationProvider delegate, Duration grace) {
        Logger log = LoggerFactory.getLogger(ConfirmationProvider.class);
        return (request, timeout) -> {
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "aura-confirm");
                t.setDaemon(true);
                return t;
            });
            try {
                Callable<Decision> task = () -> delegate.confirm(request, timeout);
                Future<Decision> future = executor.submit(task);
                Decision answer = future.get(
                    timeout.plus(grace).toMillis(), TimeUnit.MILLISECONDS);
                if (answer == Decision.ALLOW) {
                    return Decision.ALLOW;
                }
                if (answer != Decision.DENY) {
                    log.warn("подтверждение вернуло {} для {} — считаем отказом",
                        answer, request.toolName());
                }
                return Decision.DENY;
            } catch (Exception e) {
                log.info("подтверждение не получено для {} ({}) — отказ",
                    request.toolName(), e.getClass().getSimpleName());
                return Decision.DENY;
            } finally {
                executor.shutdownNow();
            }
        };
    }
}
```

- [ ] **Step 4: Написать диалог трея**

`aura-policy/src/main/java/aura/policy/TrayConfirmationProvider.java`:

```java
package aura.policy;

import java.awt.GraphicsEnvironment;
import java.time.Duration;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Модальный диалог подтверждения — путь M1, до появления голоса.
 *
 * <p>Остаётся в системе и после M4: когда микрофон занят или распознавание в
 * состоянии DEGRADED, спросить всё равно надо.
 */
public final class TrayConfirmationProvider implements ConfirmationProvider {

    @Override
    public Decision confirm(ToolRequest request, Duration timeout) {
        if (GraphicsEnvironment.isHeadless()) {
            // Без экрана спросить некого. Молча разрешать нельзя.
            return Decision.DENY;
        }
        final Decision[] answer = {Decision.DENY};
        try {
            SwingUtilities.invokeAndWait(() -> {
                JOptionPane pane = new JOptionPane(
                    "Агент хочет выполнить:\n\n" + request.toolName() + "\n"
                        + abbreviate(request.toolInputJson()) + "\n\nв каталоге "
                        + request.cwd() + "\n\nРазрешить?",
                    JOptionPane.WARNING_MESSAGE,
                    JOptionPane.YES_NO_OPTION);
                JDialog dialog = pane.createDialog(null, "Aura — подтверждение");
                dialog.setAlwaysOnTop(true);

                // Окно закрывается само. Без этого таймаут висел бы модальным
                // диалогом посреди экрана, а агент стоял бы до щелчка мышью.
                Timer timer = new Timer((int) timeout.toMillis(), e -> dialog.dispose());
                timer.setRepeats(false);
                timer.start();

                dialog.setVisible(true);
                timer.stop();
                dialog.dispose();

                Object value = pane.getValue();
                answer[0] = (value instanceof Integer i && i == JOptionPane.YES_OPTION)
                    ? Decision.ALLOW : Decision.DENY;
            });
        } catch (Exception e) {
            return Decision.DENY;
        }
        return answer[0];
    }

    private static String abbreviate(String s) {
        String flat = s == null ? "" : s.replace('\n', ' ').trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}
```

- [ ] **Step 5: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-policy -am test`
Expected: PASS, шесть тестов `ConfirmationProviderTest`.

`TrayConfirmationProvider` автотестом не покрывается: он открывает окно. Его проверка — ручной шаг в Task 14.

- [ ] **Step 6: Закоммитить**

```bash
git add aura-policy/
git commit -m "feat(policy): add confirmation gate that defaults to deny"
```

---

## Task 9: Канал между хуком и приложением

**Files:**
- Create: `aura-ipc/src/main/java/aura/ipc/HookRequest.java`
- Create: `aura-ipc/src/main/java/aura/ipc/HookResponse.java`
- Create: `aura-ipc/src/main/java/aura/ipc/HookServer.java`
- Create: `aura-ipc/src/main/java/aura/ipc/HookClient.java`
- Test: `aura-ipc/src/test/java/aura/ipc/HookChannelTest.java`

**Interfaces:**
- Consumes: ничего из предыдущих задач (модуль намеренно не знает про `Decision`)
- Produces:
  - `record HookRequest(String sessionId, String toolName, String toolInputJson, String cwd)`
  - `record HookResponse(String permissionDecision, String reason)` — значения `permissionDecision`: `"allow"`, `"deny"`, `"ask"`
  - `class HookServer implements AutoCloseable` с `HookServer(Path socketPath, Function<HookRequest, HookResponse> handler)`, `void start()`, `Path socketPath()`
  - `class HookClient` со статическим `HookResponse ask(Path socketPath, HookRequest request, Duration timeout)`

Транспорт — AF_UNIX. Проверено на целевой машине: JDK 21 на Windows 11 поднимает и серверный, и клиентский конец без JNA. Права на файл сокета наследуются от каталога, поэтому сокет живёт в `%LOCALAPPDATA%`, а не в общей временной папке.

- [ ] **Step 1: Написать падающий тест**

Создать `aura-ipc/src/test/java/aura/ipc/HookChannelTest.java`:

```java
package aura.ipc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HookChannelTest {

    private static HookRequest request() {
        return new HookRequest("s1", "Bash", "{\"command\":\"rm -rf build\"}", "C:\\work\\backend");
    }

    @Test
    void requestReachesHandlerAndVerdictComesBack(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        AtomicReference<HookRequest> seen = new AtomicReference<>();

        try (HookServer server = new HookServer(socket, req -> {
            seen.set(req);
            return new HookResponse("deny", "опасная команда");
        })) {
            server.start();

            HookResponse response = HookClient.ask(socket, request(), Duration.ofSeconds(5));

            assertThat(response.permissionDecision()).isEqualTo("deny");
            assertThat(response.reason()).isEqualTo("опасная команда");
            assertThat(seen.get().toolName()).isEqualTo("Bash");
            assertThat(seen.get().toolInputJson()).contains("rm -rf build");
            assertThat(seen.get().cwd()).isEqualTo("C:\\work\\backend");
        }
    }

    @Test
    void severalHooksAreServedOneAfterAnother(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        try (HookServer server = new HookServer(socket, req -> new HookResponse("allow", ""))) {
            server.start();
            for (int i = 0; i < 3; i++) {
                assertThat(HookClient.ask(socket, request(), Duration.ofSeconds(5)).permissionDecision())
                    .isEqualTo("allow");
            }
        }
    }

    @Test
    void handlerFailureBecomesDenyRatherThanHang(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        try (HookServer server = new HookServer(socket, req -> {
            throw new IllegalStateException("обработчик упал");
        })) {
            server.start();
            assertThat(HookClient.ask(socket, request(), Duration.ofSeconds(5)).permissionDecision())
                .isEqualTo("deny");
        }
    }

    @Test
    void missingServerYieldsDenyWithoutThrowing(@TempDir Path tmp) {
        HookResponse response =
            HookClient.ask(tmp.resolve("нет-сервера.sock"), request(), Duration.ofSeconds(1));
        assertThat(response.permissionDecision()).isEqualTo("deny");
    }

    @Test
    void socketFileIsRemovedOnClose(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        try (HookServer server = new HookServer(socket, req -> new HookResponse("allow", ""))) {
            server.start();
            assertThat(Files.exists(socket)).isTrue();
        }
        assertThat(Files.exists(socket)).isFalse();
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-ipc -am test`
Expected: FAIL — `cannot find symbol: class HookServer`.

- [ ] **Step 3: Написать типы запроса и ответа**

`aura-ipc/src/main/java/aura/ipc/HookRequest.java`:

```java
package aura.ipc;

/** Вызов инструмента, как его прислал процесс хука. Все поля — строки: модуль не трактует их. */
public record HookRequest(String sessionId, String toolName, String toolInputJson, String cwd) {
}
```

`aura-ipc/src/main/java/aura/ipc/HookResponse.java`:

```java
package aura.ipc;

/**
 * Вердикт хуку. Значения {@code permissionDecision} — те, что понимает Claude Code:
 * {@code allow}, {@code deny}, {@code ask}.
 */
public record HookResponse(String permissionDecision, String reason) {

    public static HookResponse deny(String reason) {
        return new HookResponse("deny", reason);
    }
}
```

- [ ] **Step 4: Написать сервер и клиент**

`aura-ipc/src/main/java/aura/ipc/HookServer.java`:

```java
package aura.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Сервер AF_UNIX, принимающий запросы от процессов хука.
 *
 * <p>Протокол простейший: одна строка JSON с запросом, одна строка JSON с ответом,
 * соединение закрывается. Хук — короткоживущий процесс, держать сессию незачем.
 */
public final class HookServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HookServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path socketPath;
    private final Function<HookRequest, HookResponse> handler;
    private ServerSocketChannel channel;
    private Thread acceptor;
    private volatile boolean running;

    public HookServer(Path socketPath, Function<HookRequest, HookResponse> handler) {
        this.socketPath = socketPath;
        this.handler = handler;
    }

    public Path socketPath() {
        return socketPath;
    }

    public void start() throws Exception {
        Files.createDirectories(socketPath.getParent());
        Files.deleteIfExists(socketPath);

        channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        channel.bind(UnixDomainSocketAddress.of(socketPath));
        running = true;

        acceptor = new Thread(this::acceptLoop, "aura-hook-server");
        acceptor.setDaemon(true);
        acceptor.start();
        log.info("сервер хуков слушает {}", socketPath);
    }

    private void acceptLoop() {
        while (running) {
            try (SocketChannel connection = channel.accept()) {
                serve(connection);
            } catch (Exception e) {
                if (running) {
                    log.debug("соединение хука оборвалось: {}", e.toString());
                }
            }
        }
    }

    private void serve(SocketChannel connection) throws Exception {
        var reader = new BufferedReader(
            new InputStreamReader(Channels.newInputStream(connection), StandardCharsets.UTF_8));
        var writer = new BufferedWriter(
            new OutputStreamWriter(Channels.newOutputStream(connection), StandardCharsets.UTF_8));

        String line = reader.readLine();
        if (line == null) {
            return;
        }

        HookResponse response;
        try {
            HookRequest request = MAPPER.readValue(line, HookRequest.class);
            response = handler.apply(request);
            if (response == null) {
                response = HookResponse.deny("обработчик не дал ответа");
            }
        } catch (Exception e) {
            log.warn("обработчик хука упал — отвечаем отказом", e);
            response = HookResponse.deny("внутренняя ошибка Aura");
        }

        writer.write(MAPPER.writeValueAsString(response));
        writer.write('\n');
        writer.flush();
    }

    @Override
    public void close() {
        running = false;
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Exception ignored) {
            // канал уже закрыт — ничего страшного
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (Exception e) {
            log.debug("не удалось удалить файл сокета {}", socketPath);
        }
        if (acceptor != null) {
            acceptor.interrupt();
        }
    }
}
```

`aura-ipc/src/main/java/aura/ipc/HookClient.java`:

```java
package aura.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Клиент, которым пользуется процесс хука.
 *
 * <p>Любая беда — нет сервера, обрыв, мусор в ответе — превращается в отказ.
 * Хук, не получивший ответа, не имеет права разрешить вызов.
 */
public final class HookClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HookClient() {
    }

    public static HookResponse ask(Path socketPath, HookRequest request, Duration timeout) {
        Thread caller = Thread.currentThread();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeout.toMillis());
                caller.interrupt();
            } catch (InterruptedException ignored) {
                // ответ пришёл вовремя
            }
        }, "aura-hook-client-timeout");
        watchdog.setDaemon(true);
        watchdog.start();

        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));

            var writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8));
            writer.write(MAPPER.writeValueAsString(request));
            writer.write('\n');
            writer.flush();

            var reader = new BufferedReader(
                new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                return HookResponse.deny("пустой ответ Aura");
            }
            return MAPPER.readValue(line, HookResponse.class);
        } catch (Exception e) {
            return HookResponse.deny("Aura недоступна: " + e.getClass().getSimpleName());
        } finally {
            watchdog.interrupt();
            Thread.interrupted();
        }
    }
}
```

- [ ] **Step 5: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-ipc -am test`
Expected: PASS, пять тестов `HookChannelTest`.

- [ ] **Step 6: Закоммитить**

```bash
git add aura-ipc/
git commit -m "feat(ipc): connect hook processes over an AF_UNIX socket"
```

---

## Task 10: Исполняемый хук и файл настроек агента

**Files:**
- Create: `aura-hook/src/main/java/aura/hook/HookMain.java`
- Create: `aura-app/src/main/java/aura/app/SettingsFileWriter.java`
- Test: `aura-hook/src/test/java/aura/hook/HookMainTest.java`
- Test: `aura-app/src/test/java/aura/app/SettingsFileWriterTest.java`

**Interfaces:**
- Consumes: `HookRequest`, `HookResponse`, `HookClient`, `HookServer` из Task 9
- Produces:
  - `class HookMain` с `static void main(String[])` и с выделенным `static String decide(String stdinJson, Path socketPath, Duration timeout)`, который возвращает готовую строку ответа для Claude Code
  - `class SettingsFileWriter` со статическим `Path write(Path targetFile, Path socketPath, Path hookJar, Path javaExe)`

Разделение `main` и `decide` нужно, чтобы логика тестировалась без запуска процесса.

- [ ] **Step 1: Закрыть RISK-6 до того, как написана хоть строка `HookMain`**

ADR 0003 требует выяснить контракт хука **до** реализации, а не после. Проверка
стоит полчаса и не нуждается ни в одной строке нашего кода: достаточно скрипта,
который всегда отвечает отказом.

```bash
SCRATCH="${TMPDIR:-/tmp}/aura-hook-probe"
mkdir -p "$SCRATCH/work" && cd "$SCRATCH"

cat > always-deny.cmd <<'CMD'
@echo off
echo {"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"проверка контракта"}}
CMD

cat > probe-settings.json <<JSON
{
  "hooks": {
    "PreToolUse": [
      { "matcher": "*",
        "hooks": [ { "type": "command", "command": "$SCRATCH\\\\always-deny.cmd" } ] }
    ]
  }
}
JSON

cd "$SCRATCH/work"
echo 'Run the bash command: dir' | claude -p --output-format stream-json --verbose \
  --settings "$SCRATCH/probe-settings.json" > probe.jsonl 2>probe.err

python -c "
import json
for line in open('probe.jsonl', encoding='utf-8'):
    o = json.loads(line)
    if o.get('type') == 'result':
        print('denials:', json.dumps(o.get('permission_denials'), ensure_ascii=False))
        print('result  :', o.get('result'))
"
```

Что должно получиться: `permission_denials` непуст и содержит запись про `Bash`,
команда `dir` не исполнена.

Записать наблюдения в `docs/RISKS.md`: перевести RISK-6 в закрытые с датой и
фактическим форматом ответа. **Если формат отличается от ожидаемого — исправить
ожидания в коде ниже по этой задаче, а не подгонять проверку.**

- [ ] **Step 2: Написать падающий тест хука**

Создать `aura-hook/src/test/java/aura/hook/HookMainTest.java`:

```java
package aura.hook;

import static org.assertj.core.api.Assertions.assertThat;

import aura.ipc.HookResponse;
import aura.ipc.HookServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HookMainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CLAUDE_PAYLOAD = """
        {"session_id":"aba8e22f","cwd":"C:\\\\work\\\\backend",
         "tool_name":"Bash","tool_input":{"command":"rm -rf build"}}""";

    @Test
    void wrapsVerdictInTheShapeClaudeExpects(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        try (HookServer server = new HookServer(socket, req -> new HookResponse("deny", "нельзя"))) {
            server.start();

            String out = HookMain.decide(CLAUDE_PAYLOAD, socket, Duration.ofSeconds(5));
            JsonNode node = MAPPER.readTree(out);

            assertThat(node.at("/hookSpecificOutput/hookEventName").asText()).isEqualTo("PreToolUse");
            assertThat(node.at("/hookSpecificOutput/permissionDecision").asText()).isEqualTo("deny");
            assertThat(node.at("/hookSpecificOutput/permissionDecisionReason").asText()).isEqualTo("нельзя");
        }
    }

    @Test
    void forwardsToolNameInputAndCwdToAura(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        AtomicReference<String> seenInput = new AtomicReference<>();
        AtomicReference<String> seenCwd = new AtomicReference<>();

        try (HookServer server = new HookServer(socket, req -> {
            seenInput.set(req.toolInputJson());
            seenCwd.set(req.cwd());
            return new HookResponse("allow", "");
        })) {
            server.start();
            HookMain.decide(CLAUDE_PAYLOAD, socket, Duration.ofSeconds(5));
        }

        assertThat(seenInput.get()).contains("rm -rf build");
        assertThat(seenCwd.get()).isEqualTo("C:\\work\\backend");
    }

    @Test
    void deniesWhenAuraIsNotListening(@TempDir Path tmp) throws Exception {
        String out = HookMain.decide(CLAUDE_PAYLOAD, tmp.resolve("нет.sock"), Duration.ofSeconds(1));
        assertThat(MAPPER.readTree(out).at("/hookSpecificOutput/permissionDecision").asText())
            .isEqualTo("deny");
    }

    @Test
    void deniesOnMalformedStdinPayload(@TempDir Path tmp) throws Exception {
        String out = HookMain.decide("не json", tmp.resolve("нет.sock"), Duration.ofSeconds(1));
        assertThat(MAPPER.readTree(out).at("/hookSpecificOutput/permissionDecision").asText())
            .isEqualTo("deny");
    }
}
```

- [ ] **Step 3: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-hook -am test`
Expected: FAIL — `cannot find symbol: class HookMain`.

- [ ] **Step 4: Написать хук**

`aura-hook/src/main/java/aura/hook/HookMain.java`:

```java
package aura.hook;

import aura.ipc.HookClient;
import aura.ipc.HookRequest;
import aura.ipc.HookResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Хук {@code PreToolUse}, который Claude Code запускает перед каждым вызовом
 * инструмента. Читает описание вызова со stdin, спрашивает Aura через сокет и
 * печатает вердикт в stdout.
 *
 * <p>Процесс короткоживущий и обязан быть быстрым в отказе: если Aura не
 * отвечает, единственный допустимый ответ — deny.
 */
public final class HookMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);

    public static void main(String[] args) throws Exception {
        String stdin = new String(readAll(System.in), StandardCharsets.UTF_8);
        System.out.println(decide(stdin, socketPath(args), DEFAULT_TIMEOUT));
    }

    /** Аргумент главнее переменной окружения: он не зависит от того, что агент передаёт хуку. */
    static Path socketPath(String[] args) {
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0]);
        }
        String fromEnv = System.getenv("AURA_HOOK_SOCKET");
        return Path.of(fromEnv == null ? "нет-сокета" : fromEnv);
    }

    /** Выделено из {@link #main} ради тестируемости без запуска процесса. */
    public static String decide(String stdinJson, Path socketPath, Duration timeout) {
        HookResponse response;
        try {
            JsonNode payload = MAPPER.readTree(stdinJson);
            HookRequest request = new HookRequest(
                payload.path("session_id").asText(""),
                payload.path("tool_name").asText(""),
                payload.path("tool_input").toString(),
                payload.path("cwd").asText(""));
            response = HookClient.ask(socketPath, request, timeout);
        } catch (Exception e) {
            response = HookResponse.deny("хук не разобрал запрос");
        }
        return render(response);
    }

    private static String render(HookResponse response) {
        ObjectNode inner = MAPPER.createObjectNode();
        inner.put("hookEventName", "PreToolUse");
        inner.put("permissionDecision", response.permissionDecision());
        inner.put("permissionDecisionReason", response.reason() == null ? "" : response.reason());

        ObjectNode root = MAPPER.createObjectNode();
        root.set("hookSpecificOutput", inner);
        return root.toString();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        return in.readAllBytes();
    }

    private HookMain() {
    }
}
```

- [ ] **Step 5: Написать генератор файла настроек**

Создать `aura-app/src/test/java/aura/app/SettingsFileWriterTest.java`:

```java
package aura.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsFileWriterTest {

    @Test
    void writesPreToolUseHookPointingAtOurJar(@TempDir Path tmp) throws Exception {
        Path settings = tmp.resolve("aura-settings.json");
        Path jar = tmp.resolve("aura-hook.jar");
        Path java = Path.of("C:", "jdk", "bin", "java.exe");
        Path socket = tmp.resolve("aura.sock");

        SettingsFileWriter.write(settings, socket, jar, java);

        JsonNode root = new ObjectMapper()
            .readTree(Files.readString(settings, StandardCharsets.UTF_8));
        JsonNode matcher = root.at("/hooks/PreToolUse/0");

        assertThat(matcher.path("matcher").asText()).isEqualTo("*");
        String command = matcher.at("/hooks/0/command").asText();
        assertThat(command)
            .contains(java.toString())
            .contains(jar.toString())
            .contains(socket.toString());
        assertThat(matcher.at("/hooks/0/type").asText()).isEqualTo("command");
        assertThat(root.at("/env/AURA_HOOK_SOCKET").asText()).isEqualTo(socket.toString());
    }

    @Test
    void neverEmitsBypassPermissions(@TempDir Path tmp) throws Exception {
        Path settings = tmp.resolve("aura-settings.json");
        SettingsFileWriter.write(settings, tmp.resolve("s.sock"),
            tmp.resolve("h.jar"), Path.of("java"));

        assertThat(Files.readString(settings, StandardCharsets.UTF_8))
            .doesNotContain("bypassPermissions");
    }
}
```

`aura-app/src/main/java/aura/app/SettingsFileWriter.java`:

```java
package aura.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Пишет временный файл настроек, который передаётся агенту через
 * {@code --settings}. Глобальная конфигурация пользователя не трогается.
 *
 * <p>Файл содержит ровно один хук и ничего больше: чем меньше в нём написано,
 * тем меньше шума в потоке событий и тем меньше поводов для сюрпризов.
 */
public final class SettingsFileWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SettingsFileWriter() {
    }

    public static Path write(Path targetFile, Path socketPath, Path hookJar, Path javaExe) {
        ObjectNode hookEntry = MAPPER.createObjectNode();
        hookEntry.put("type", "command");
        // Путь к сокету передаётся аргументом, а не только переменной окружения:
        // распространяется ли env из этого файла на процесс хука — как раз то,
        // что проверяет RISK-6. Аргумент работает независимо от ответа.
        hookEntry.put("command",
            "\"" + javaExe + "\" -jar \"" + hookJar + "\" \"" + socketPath + "\"");

        ArrayNode hookList = MAPPER.createArrayNode();
        hookList.add(hookEntry);

        ObjectNode matcher = MAPPER.createObjectNode();
        matcher.put("matcher", "*");
        matcher.set("hooks", hookList);

        ArrayNode preToolUse = MAPPER.createArrayNode();
        preToolUse.add(matcher);

        ObjectNode hooks = MAPPER.createObjectNode();
        hooks.set("PreToolUse", preToolUse);

        ObjectNode env = MAPPER.createObjectNode();
        env.put("AURA_HOOK_SOCKET", socketPath.toString());

        ObjectNode root = MAPPER.createObjectNode();
        root.set("hooks", hooks);
        root.set("env", env);

        try {
            Files.createDirectories(targetFile.getParent());
            Files.writeString(targetFile, root.toPrettyString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("не удалось записать настройки агента: " + targetFile, e);
        }
        return targetFile;
    }
}
```

- [ ] **Step 6: Запустить тесты и убедиться, что проходят**

Run: `mvn -q test`
Expected: PASS во всех модулях, включая четыре теста `HookMainTest` и два `SettingsFileWriterTest`.

- [ ] **Step 7: Закоммитить**

```bash
git add aura-hook/ aura-app/
git commit -m "feat(hook): ask Aura before each tool call, deny when unreachable"
```

---

## Task 11: Долгоживущая сессия агента

**Files:**
- Create: `aura-agents/src/main/java/aura/agents/SessionConfig.java`
- Create: `aura-agents/src/main/java/aura/agents/AgentSession.java`
- Create: `aura-agents/src/main/java/aura/agents/ClaudeSession.java`
- Test: `aura-agents/src/test/java/aura/agents/FakeAgentMain.java`
- Test: `aura-agents/src/test/java/aura/agents/ClaudeSessionTest.java`

**Interfaces:**
- Consumes: `ClaudeEventParser` из Task 3, `AgentEvent` из Task 2
- Produces:
  - `record SessionConfig(List<String> command, Path workingDir, String sessionId)`
  - `interface AgentSession extends AutoCloseable { void send(String userText); String sessionId(); boolean alive(); void close(); }`
  - `class ClaudeSession implements AgentSession` со статическим `ClaudeSession start(SessionConfig config, Consumer<AgentEvent> sink)`

Тесты не запускают настоящий `claude`: вместо него — `FakeAgentMain` из тестовых исходников. Он читает строки stdin в формате `stream-json` и выдаёт заранее заданный поток. Так проверяется вся механика процесса, не тратя ни токена и не завися от сети.

- [ ] **Step 1: Написать поддельного агента**

Создать `aura-agents/src/test/java/aura/agents/FakeAgentMain.java`:

```java
package aura.agents;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Поддельный агент для тестов. Ведёт себя как {@code claude -p --input-format
 * stream-json --output-format stream-json}: читает по строке JSON со stdin и на
 * каждую выдаёт короткий поток событий.
 *
 * <p>Существует, чтобы тесты процессной механики не ходили в сеть и не тратили
 * токены. Живёт в тестовых исходниках и в поставку не попадает.
 */
public final class FakeAgentMain {

    public static void main(String[] args) throws Exception {
        String sessionId = args.length > 0 ? args[0] : "fake-session";

        System.out.println("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\""
            + sessionId + "\",\"cwd\":\"C:/fake\"}");
        System.out.flush();

        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        int turn = 0;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            if (line.contains("\"СТОП\"")) {
                break;
            }
            // Идентификатор вызова уникален на ход. Одинаковый id на всех ходах
            // прятал бы коллизию в таблице ожидающих вызовов парсера: тест
            // проходил бы по случайности.
            String toolUseId = "toolu_fake_" + (++turn);

            System.out.println("{\"type\":\"assistant\",\"session_id\":\"" + sessionId
                + "\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"" + toolUseId
                + "\",\"name\":\"Bash\",\"input\":{\"command\":\"echo hi\"}}]}}");
            System.out.println("{\"type\":\"user\",\"session_id\":\"" + sessionId
                + "\",\"message\":{\"content\":[{\"type\":\"tool_result\","
                + "\"tool_use_id\":\"" + toolUseId + "\",\"content\":\"hi\",\"is_error\":false}]},"
                + "\"tool_use_result\":{\"stdout\":\"hi\",\"stderr\":\"\"}}");
            System.out.println("{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\""
                + sessionId + "\",\"result\":\"готово\",\"is_error\":false}");
            System.out.flush();
        }
    }

    private FakeAgentMain() {
    }
}
```

- [ ] **Step 2: Написать падающий тест сессии**

Создать `aura-agents/src/test/java/aura/agents/ClaudeSessionTest.java`:

```java
package aura.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import aura.core.AgentEvent;
import aura.core.EventKind;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ClaudeSessionTest {

    /** Запускает поддельного агента текущей JVM: тот же java, тот же classpath. */
    private static SessionConfig fakeAgentConfig() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        return new SessionConfig(
            List.of(java, "-cp", classpath, "aura.agents.FakeAgentMain", "s-test"),
            Path.of("."),
            "s-test");
    }

    @Test
    void emitsSessionStartOnLaunch() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (ClaudeSession session = ClaudeSession.start(fakeAgentConfig(), events::add)) {
            await().atMost(Duration.ofSeconds(10))
                .until(() -> !events.isEmpty());
            assertThat(events.get(0).kind()).isEqualTo(EventKind.SESSION_START);
            assertThat(session.alive()).isTrue();
        }
    }

    @Test
    void userTurnGoesIntoTheSameProcessAndProducesEvents() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (ClaudeSession session = ClaudeSession.start(fakeAgentConfig(), events::add)) {
            session.send("почини тесты");

            await().atMost(Duration.ofSeconds(10)).until(() ->
                events.stream().anyMatch(e -> e.kind() == EventKind.DONE));

            assertThat(events).extracting(AgentEvent::kind).contains(
                EventKind.SESSION_START, EventKind.TOOL_START,
                EventKind.TOOL_END, EventKind.DONE);
        }
    }

    @Test
    void secondTurnReusesTheSameProcessWithoutRestart() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (ClaudeSession session = ClaudeSession.start(fakeAgentConfig(), events::add)) {
            session.send("первая задача");
            await().atMost(Duration.ofSeconds(10)).until(() ->
                events.stream().filter(e -> e.kind() == EventKind.DONE).count() == 1);

            session.send("вторая задача");
            await().atMost(Duration.ofSeconds(10)).until(() ->
                events.stream().filter(e -> e.kind() == EventKind.DONE).count() == 2);

            // Ровно один SESSION_START на два хода — процесс не перезапускался.
            assertThat(events).filteredOn(e -> e.kind() == EventKind.SESSION_START).hasSize(1);
        }
    }

    @Test
    void closeTerminatesTheProcess() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        ClaudeSession session = ClaudeSession.start(fakeAgentConfig(), events::add);
        await().atMost(Duration.ofSeconds(10)).until(() -> !events.isEmpty());

        session.close();

        await().atMost(Duration.ofSeconds(10)).until(() -> !session.alive());
    }
}
```

Добавить в `aura-agents/pom.xml` тестовую зависимость Awaitility, а в родительский POM — её версию:

```xml
<!-- в <properties> родительского POM -->
<awaitility.version>4.2.1</awaitility.version>

<!-- в <dependencyManagement> родительского POM -->
<dependency>
  <groupId>org.awaitility</groupId>
  <artifactId>awaitility</artifactId>
  <version>${awaitility.version}</version>
</dependency>

<!-- в <dependencies> модуля aura-agents -->
<dependency>
  <groupId>org.awaitility</groupId>
  <artifactId>awaitility</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-agents -am test`
Expected: FAIL — `cannot find symbol: class ClaudeSession`.

- [ ] **Step 4: Написать типы сессии**

`aura-agents/src/main/java/aura/agents/SessionConfig.java`:

```java
package aura.agents;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Полностью собранная команда запуска агента.
 *
 * <p>Команда приходит готовым списком, а не собирается здесь: аргументы зависят
 * от агента и от проекта, и место их сборки — {@code aura-app}.
 */
public record SessionConfig(List<String> command, Path workingDir, String sessionId) {
    public SessionConfig {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("пустая команда запуска агента");
        }
        command = List.copyOf(command);
        Objects.requireNonNull(workingDir, "workingDir");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
```

`aura-agents/src/main/java/aura/agents/AgentSession.java`:

```java
package aura.agents;

/**
 * Сессия агента. Реализации отличаются характеристиками латентности: у Claude
 * Code процесс живёт между репликами, у Codex каждая реплика поднимает
 * {@code exec resume}. Асимметрия названа намеренно, а не спрятана.
 */
public interface AgentSession extends AutoCloseable {

    /** Отправляет пользовательскую реплику в сессию. */
    void send(String userText);

    String sessionId();

    boolean alive();

    @Override
    void close();
}
```

- [ ] **Step 5: Написать `ClaudeSession`**

`aura-agents/src/main/java/aura/agents/ClaudeSession.java`:

```java
package aura.agents;

import aura.core.AgentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Долгоживущий процесс {@code claude} с потоковым вводом и выводом.
 *
 * <p>Реплики уходят строкой в stdin того же процесса, поэтому уточнение на ходу
 * не платит за холодный старт. Взамен процесс висит в памяти — за его
 * завершением следит {@link SessionSupervisor}.
 */
public final class ClaudeSession implements AgentSession {

    private static final Logger log = LoggerFactory.getLogger(ClaudeSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Process process;
    private final BufferedWriter stdin;
    private final String sessionId;
    private final Thread stdoutReader;
    private final Thread stderrReader;

    private ClaudeSession(Process process, String sessionId, Consumer<AgentEvent> sink) {
        this.process = process;
        this.sessionId = sessionId;
        this.stdin = new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        ClaudeEventParser parser = new ClaudeEventParser(Clock.systemUTC());
        this.stdoutReader = pump(process.getInputStream(), line -> {
            for (AgentEvent event : parser.parseLine(line)) {
                try {
                    sink.accept(event);
                } catch (Exception e) {
                    log.warn("получатель событий бросил исключение", e);
                }
            }
        }, "aura-agent-stdout");

        this.stderrReader = pump(process.getErrorStream(),
            line -> log.debug("claude stderr: {}", line), "aura-agent-stderr");
    }

    public static ClaudeSession start(SessionConfig config, Consumer<AgentEvent> sink)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(config.command())
            .directory(config.workingDir().toFile());
        Process process = builder.start();
        log.info("агент запущен, pid={}, сессия={}", process.pid(), config.sessionId());
        return new ClaudeSession(process, config.sessionId(), sink);
    }

    @Override
    public void send(String userText) {
        ObjectNode content = MAPPER.createObjectNode();
        content.put("type", "text");
        content.put("text", userText);

        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "user");
        message.set("content", MAPPER.createArrayNode().add(content));

        ObjectNode line = MAPPER.createObjectNode();
        line.put("type", "user");
        line.set("message", message);

        try {
            stdin.write(line.toString());
            stdin.write('\n');
            stdin.flush();
        } catch (Exception e) {
            throw new IllegalStateException("не удалось отправить реплику агенту", e);
        }
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public boolean alive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (Exception ignored) {
            // процесс мог уже закрыть свой конец
        }
        // Дерево, а не только корень: агент запускает дочерние процессы,
        // и осиротевший pytest продолжит жить, если убить только родителя.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        stdoutReader.interrupt();
        stderrReader.interrupt();
    }

    private static Thread pump(java.io.InputStream in, Consumer<String> onLine, String name) {
        Thread thread = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (Exception e) {
                log.debug("поток {} закрыт: {}", name, e.toString());
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
```

- [ ] **Step 6: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-agents -am test`
Expected: PASS, четыре теста `ClaudeSessionTest`.

- [ ] **Step 7: Закоммитить**

```bash
git add aura-agents/ pom.xml
git commit -m "feat(agents): keep one long-lived Claude process per project"
```

---

## Task 12: Супервизор сессий

**Files:**
- Create: `aura-agents/src/main/java/aura/agents/SessionSupervisor.java`
- Test: `aura-agents/src/test/java/aura/agents/SessionSupervisorTest.java`

**Interfaces:**
- Consumes: `AgentSession`, `SessionConfig`, `ClaudeSession.start` из Task 11
- Produces: `class SessionSupervisor implements AutoCloseable` с
  - `SessionSupervisor(SessionFactory factory, Duration idleTimeout, Clock clock)`
  - вложенным `interface SessionFactory { AgentSession create(SessionConfig config, Consumer<AgentEvent> sink) throws Exception; }`
  - `AgentSession sessionFor(String key, SessionConfig config, Consumer<AgentEvent> sink)`
  - `void stop(String key)`
  - `int closeIdle()` — возвращает число закрытых сессий

- [ ] **Step 1: Написать падающий тест**

Создать `aura-agents/src/test/java/aura/agents/SessionSupervisorTest.java`:

```java
package aura.agents;

import static org.assertj.core.api.Assertions.assertThat;

import aura.core.AgentEvent;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionSupervisorTest {

    private static final SessionConfig CONFIG =
        new SessionConfig(List.of("java", "-version"), Path.of("."), "s1");

    /** Управляемые часы: тест не должен ждать реальный таймаут простоя. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-08-26T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class FakeSession implements AgentSession {
        boolean closed;
        boolean aliveFlag = true;
        final List<String> sent = new java.util.ArrayList<>();

        @Override public void send(String userText) { sent.add(userText); }
        @Override public String sessionId() { return "s1"; }
        @Override public boolean alive() { return aliveFlag && !closed; }
        @Override public void close() { closed = true; }
    }

    @Test
    void reusesTheSameSessionForTheSameKey() throws Exception {
        AtomicInteger created = new AtomicInteger();
        var supervisor = new SessionSupervisor(
            (cfg, sink) -> { created.incrementAndGet(); return new FakeSession(); },
            Duration.ofMinutes(10), new MovableClock());

        AgentSession first = supervisor.sessionFor("backend", CONFIG, e -> {});
        AgentSession second = supervisor.sessionFor("backend", CONFIG, e -> {});

        assertThat(second).isSameAs(first);
        assertThat(created).hasValue(1);
        supervisor.close();
    }

    @Test
    void createsSeparateSessionsForSeparateProjects() throws Exception {
        AtomicInteger created = new AtomicInteger();
        var supervisor = new SessionSupervisor(
            (cfg, sink) -> { created.incrementAndGet(); return new FakeSession(); },
            Duration.ofMinutes(10), new MovableClock());

        supervisor.sessionFor("backend", CONFIG, e -> {});
        supervisor.sessionFor("frontend", CONFIG, e -> {});

        assertThat(created).hasValue(2);
        supervisor.close();
    }

    @Test
    void deadSessionIsReplacedOnNextRequest() throws Exception {
        AtomicInteger created = new AtomicInteger();
        FakeSession[] made = new FakeSession[1];
        var supervisor = new SessionSupervisor((cfg, sink) -> {
            created.incrementAndGet();
            made[0] = new FakeSession();
            return made[0];
        }, Duration.ofMinutes(10), new MovableClock());

        supervisor.sessionFor("backend", CONFIG, e -> {});
        made[0].aliveFlag = false;
        supervisor.sessionFor("backend", CONFIG, e -> {});

        assertThat(created).hasValue(2);
        supervisor.close();
    }

    @Test
    void idleSessionIsClosedAfterTimeout() throws Exception {
        MovableClock clock = new MovableClock();
        FakeSession[] made = new FakeSession[1];
        var supervisor = new SessionSupervisor((cfg, sink) -> {
            made[0] = new FakeSession();
            return made[0];
        }, Duration.ofMinutes(5), clock);

        supervisor.sessionFor("backend", CONFIG, e -> {});
        clock.advance(Duration.ofMinutes(4));
        assertThat(supervisor.closeIdle()).isZero();

        clock.advance(Duration.ofMinutes(2));
        assertThat(supervisor.closeIdle()).isEqualTo(1);
        assertThat(made[0].closed).isTrue();
        supervisor.close();
    }

    @Test
    void activityResetsTheIdleClock() throws Exception {
        MovableClock clock = new MovableClock();
        var supervisor = new SessionSupervisor((cfg, sink) -> new FakeSession(),
            Duration.ofMinutes(5), clock);

        supervisor.sessionFor("backend", CONFIG, e -> {});
        clock.advance(Duration.ofMinutes(4));
        supervisor.sessionFor("backend", CONFIG, e -> {});   // обращение — это активность
        clock.advance(Duration.ofMinutes(4));

        assertThat(supervisor.closeIdle()).isZero();
        supervisor.close();
    }

    @Test
    void stopClosesOneSessionAndForgetsIt() throws Exception {
        FakeSession[] made = new FakeSession[1];
        AtomicInteger created = new AtomicInteger();
        var supervisor = new SessionSupervisor((cfg, sink) -> {
            created.incrementAndGet();
            made[0] = new FakeSession();
            return made[0];
        }, Duration.ofMinutes(5), new MovableClock());

        supervisor.sessionFor("backend", CONFIG, e -> {});
        supervisor.stop("backend");

        assertThat(made[0].closed).isTrue();
        supervisor.sessionFor("backend", CONFIG, e -> {});
        assertThat(created).hasValue(2);
        supervisor.close();
    }

    @Test
    void closeShutsDownEverySession() throws Exception {
        List<FakeSession> made = new java.util.ArrayList<>();
        var supervisor = new SessionSupervisor((cfg, sink) -> {
            FakeSession s = new FakeSession();
            made.add(s);
            return s;
        }, Duration.ofMinutes(5), new MovableClock());

        supervisor.sessionFor("a", CONFIG, e -> {});
        supervisor.sessionFor("b", CONFIG, e -> {});
        supervisor.close();

        assertThat(made).allMatch(s -> s.closed);
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-agents -am test`
Expected: FAIL — `cannot find symbol: class SessionSupervisor`.

- [ ] **Step 3: Написать супервизор**

`aura-agents/src/main/java/aura/agents/SessionSupervisor.java`:

```java
package aura.agents;

import aura.core.AgentEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Хранит по одной сессии на проект и следит за их жизненным циклом.
 *
 * <p>Часы передаются снаружи: тайм-аут простоя должен проверяться тестом за
 * миллисекунды, а не за пять реальных минут.
 */
public final class SessionSupervisor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionSupervisor.class);

    @FunctionalInterface
    public interface SessionFactory {
        AgentSession create(SessionConfig config, Consumer<AgentEvent> sink) throws Exception;
    }

    private record Entry(AgentSession session, Instant lastUsed) {}

    private final SessionFactory factory;
    private final Duration idleTimeout;
    private final Clock clock;
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

    public SessionSupervisor(SessionFactory factory, Duration idleTimeout, Clock clock) {
        this.factory = factory;
        this.idleTimeout = idleTimeout;
        this.clock = clock;
    }

    /** Возвращает живую сессию для ключа, создавая или пересоздавая её при необходимости. */
    public AgentSession sessionFor(String key, SessionConfig config, Consumer<AgentEvent> sink) {
        Entry existing = sessions.get(key);
        if (existing != null && existing.session().alive()) {
            sessions.put(key, new Entry(existing.session(), clock.instant()));
            return existing.session();
        }
        if (existing != null) {
            log.info("сессия {} мертва — пересоздаём", key);
            safeClose(existing.session());
        }
        try {
            AgentSession created = factory.create(config, sink);
            sessions.put(key, new Entry(created, clock.instant()));
            return created;
        } catch (Exception e) {
            sessions.remove(key);
            throw new IllegalStateException("не удалось запустить агента для " + key, e);
        }
    }

    public void stop(String key) {
        Entry entry = sessions.remove(key);
        if (entry != null) {
            log.info("останавливаем сессию {}", key);
            safeClose(entry.session());
        }
    }

    /** Закрывает сессии, к которым не обращались дольше тайм-аута. */
    public int closeIdle() {
        Instant now = clock.instant();
        List<String> expired = new ArrayList<>();
        sessions.forEach((key, entry) -> {
            if (Duration.between(entry.lastUsed(), now).compareTo(idleTimeout) > 0) {
                expired.add(key);
            }
        });
        expired.forEach(this::stop);
        return expired.size();
    }

    @Override
    public void close() {
        List.copyOf(sessions.keySet()).forEach(this::stop);
    }

    private static void safeClose(AgentSession session) {
        try {
            session.close();
        } catch (Exception e) {
            log.debug("сессия закрылась с ошибкой: {}", e.toString());
        }
    }
}
```

- [ ] **Step 4: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-agents -am test`
Expected: PASS, семь тестов `SessionSupervisorTest`.

- [ ] **Step 5: Закоммитить**

```bash
git add aura-agents/
git commit -m "feat(agents): supervise one session per project with idle timeout"
```

---

## Task 13: Протокол сайдкара и его заглушка

**Files:**
- Create: `sidecar/aura_speech/stub.py`
- Create: `aura-ipc/src/main/java/aura/ipc/SpeechClient.java`
- Test: `aura-ipc/src/test/java/aura/ipc/SpeechClientTest.java`

**Interfaces:**
- Consumes: ничего из предыдущих задач
- Produces: `class SpeechClient implements AutoCloseable` с
  - `static SpeechClient start(List<String> command, Path workingDir, Consumer<JsonNode> onEvent)`
  - `void send(Map<String, Object> command)`
  - `boolean alive()`
  - `void close()`

Заглушка говорит по протоколу §6 дизайна, но не грузит моделей. Её задача — зафиксировать контракт до того, как появится настоящий сайдкар: когда в M2 приедут модели, ломаться будет реализация, а не форма сообщений.

- [ ] **Step 1: Написать заглушку сайдкара**

Создать `sidecar/aura_speech/stub.py`:

```python
"""Заглушка сайдкара: говорит по протоколу, но не грузит моделей.

Существует, чтобы контракт JSON-lines был зафиксирован и покрыт тестами до
появления настоящего звука в M2. Ничего не воспроизводит и ничего не слушает.
"""

import json
import sys


def emit(payload):
    sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main():
    emit({
        "ev": "ready",
        "devices": {"npu": False, "gpu": False},
        "models": {"stt": "stub", "slm": "stub", "tts": "stub"},
        "stub": True,
    })

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            emit({"ev": "error", "code": "BAD_JSON", "detail": line[:200], "fatal": False})
            continue

        command = message.get("cmd")
        message_id = message.get("id", "")

        if command == "shutdown":
            break
        if command == "configure":
            continue
        if command == "speak":
            emit({"ev": "speak.started", "for": message_id})
            emit({"ev": "speak.done", "for": message_id})
            continue
        if command == "narrate":
            events = message.get("events", [])
            emit({"ev": "narration", "text": f"заглушка: событий {len(events)}", "for": message_id})
            emit({"ev": "speak.started", "for": message_id})
            emit({"ev": "speak.done", "for": message_id})
            continue
        if command == "speak.cancel":
            continue

        emit({"ev": "error", "code": "UNKNOWN_COMMAND", "detail": str(command), "fatal": False})


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Написать падающий тест контракта**

Создать `aura-ipc/src/test/java/aura/ipc/SpeechClientTest.java`:

```java
package aura.ipc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SpeechClientTest {

    private static final Path STUB = Path.of("..", "sidecar", "aura_speech", "stub.py");

    private static String python() {
        String fromEnv = System.getenv("AURA_PYTHON");
        return fromEnv == null ? "python" : fromEnv;
    }

    @BeforeAll
    static void sidecarStubExists() {
        assumeTrue(Files.isRegularFile(STUB), "заглушка сайдкара не найдена");
    }

    private SpeechClient startStub(List<JsonNode> sink) throws Exception {
        return SpeechClient.start(List.of(python(), STUB.toString()), Path.of("."), sink::add);
    }

    @Test
    void announcesItselfReadyOnStart() throws Exception {
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (SpeechClient client = startStub(events)) {
            await().atMost(Duration.ofSeconds(15)).until(() -> !events.isEmpty());
            assertThat(events.get(0).path("ev").asText()).isEqualTo("ready");
            assertThat(events.get(0).path("stub").asBoolean()).isTrue();
        }
    }

    @Test
    void speakCommandIsAcknowledgedByStartAndDone() throws Exception {
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (SpeechClient client = startStub(events)) {
            await().atMost(Duration.ofSeconds(15)).until(() -> !events.isEmpty());

            client.send(Map.of("id", "c2", "cmd", "speak", "text", "Клод взялся"));

            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().anyMatch(e -> "speak.done".equals(e.path("ev").asText())));

            assertThat(events).extracting(e -> e.path("ev").asText())
                .containsSequence("speak.started", "speak.done");
            assertThat(events).filteredOn(e -> "speak.started".equals(e.path("ev").asText()))
                .allMatch(e -> "c2".equals(e.path("for").asText()));
        }
    }

    @Test
    void narrateProducesNarrationBeforeSpeaking() throws Exception {
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (SpeechClient client = startStub(events)) {
            await().atMost(Duration.ofSeconds(15)).until(() -> !events.isEmpty());

            client.send(Map.of("id", "c4", "cmd", "narrate",
                "style", "progress", "events", List.of(Map.of("kind", "TOOL_START"))));

            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().anyMatch(e -> "narration".equals(e.path("ev").asText())));

            assertThat(events).extracting(e -> e.path("ev").asText())
                .containsSequence("narration", "speak.started", "speak.done");
        }
    }

    @Test
    void unknownCommandYieldsErrorEventInsteadOfSilence() throws Exception {
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        try (SpeechClient client = startStub(events)) {
            await().atMost(Duration.ofSeconds(15)).until(() -> !events.isEmpty());

            client.send(Map.of("id", "cX", "cmd", "полети_на_луну"));

            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().anyMatch(e -> "error".equals(e.path("ev").asText())));

            assertThat(events).filteredOn(e -> "error".equals(e.path("ev").asText()))
                .allMatch(e -> "UNKNOWN_COMMAND".equals(e.path("code").asText()));
        }
    }

    @Test
    void shutdownEndsTheProcess() throws Exception {
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        SpeechClient client = startStub(events);
        await().atMost(Duration.ofSeconds(15)).until(() -> !events.isEmpty());

        client.send(Map.of("id", "c10", "cmd", "shutdown"));

        await().atMost(Duration.ofSeconds(15)).until(() -> !client.alive());
        client.close();
    }
}
```

Добавить Awaitility в тестовые зависимости `aura-ipc/pom.xml` тем же блоком, что и в Task 11.

- [ ] **Step 3: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-ipc -am test`
Expected: FAIL — `cannot find symbol: class SpeechClient`.

- [ ] **Step 4: Написать клиент**

`aura-ipc/src/main/java/aura/ipc/SpeechClient.java`:

```java
package aura.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Клиент сайдкара речи: JSON-lines через stdio.
 *
 * <p>Аудио через эту границу не ходит ни в одну сторону — только команды и
 * события. Так и задумано: PCM живёт в сайдкаре, JVM его не касается.
 */
public final class SpeechClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpeechClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Process process;
    private final BufferedWriter stdin;
    private final Thread stdoutReader;
    private final Thread stderrReader;

    private SpeechClient(Process process, Consumer<JsonNode> onEvent) {
        this.process = process;
        this.stdin = new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        this.stdoutReader = pump(process.getInputStream(), line -> {
            try {
                onEvent.accept(MAPPER.readTree(line));
            } catch (Exception e) {
                log.warn("сайдкар прислал нечитаемую строку: {}", line);
            }
        }, "aura-speech-stdout");

        this.stderrReader = pump(process.getErrorStream(),
            line -> log.debug("сайдкар: {}", line), "aura-speech-stderr");
    }

    public static SpeechClient start(List<String> command, Path workingDir,
                                     Consumer<JsonNode> onEvent) throws Exception {
        Process process = new ProcessBuilder(command)
            .directory(workingDir.toFile())
            .start();
        log.info("сайдкар запущен, pid={}", process.pid());
        return new SpeechClient(process, onEvent);
    }

    public void send(Map<String, Object> command) {
        try {
            stdin.write(MAPPER.writeValueAsString(command));
            stdin.write('\n');
            stdin.flush();
        } catch (Exception e) {
            throw new IllegalStateException("не удалось отправить команду сайдкару", e);
        }
    }

    public boolean alive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            stdin.close();
        } catch (Exception ignored) {
            // сайдкар уже закрыл свой конец
        }
        process.destroy();
        stdoutReader.interrupt();
        stderrReader.interrupt();
    }

    private static Thread pump(java.io.InputStream in, Consumer<String> onLine, String name) {
        Thread thread = new Thread(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        onLine.accept(line);
                    }
                }
            } catch (Exception e) {
                log.debug("поток {} закрыт: {}", name, e.toString());
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
```

- [ ] **Step 5: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-ipc -am test`
Expected: PASS, пять тестов `SpeechClientTest` плюс пять из Task 9.

Если Python называется иначе — выставить `AURA_PYTHON` и повторить.

- [ ] **Step 6: Закоммитить**

```bash
git add sidecar/ aura-ipc/
git commit -m "feat(ipc): pin the sidecar JSON-lines contract with a model-free stub"
```

---

## Task 14: Сессия Codex

**Files:**
- Create: `aura-agents/src/main/java/aura/agents/CodexSession.java`
- Test: `aura-agents/src/test/java/aura/agents/FakeCodexMain.java`
- Test: `aura-agents/src/test/java/aura/agents/CodexSessionTest.java`

**Interfaces:**
- Consumes: `AgentSession`, `SessionConfig` из Task 11, `CodexEventParser` из Task 4
- Produces: `class CodexSession implements AgentSession` со статическим
  `CodexSession start(SessionConfig config, Consumer<AgentEvent> sink)`

Асимметрия названа в ADR 0004 и здесь воплощается: у `codex exec` нет потокового ввода, поэтому каждая реплика поднимает новый процесс. Первая реплика идёт как `exec`, последующие — как `exec resume --last`. Признак «первая или нет» держит сама сессия.

- [ ] **Step 1: Написать поддельного Codex**

Создать `aura-agents/src/test/java/aura/agents/FakeCodexMain.java`:

```java
package aura.agents;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Поддельный {@code codex exec}: читает промпт со stdin, печатает поток JSONL и
 * завершается. Дополнительно выводит полученные аргументы отдельной строкой,
 * чтобы тест мог проверить наличие {@code resume}.
 */
public final class FakeCodexMain {

    public static void main(String[] args) throws Exception {
        List<String> arguments = Arrays.asList(args);

        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder prompt = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            prompt.append(line);
        }

        System.out.println("{\"type\":\"thread.started\",\"thread_id\":\"t-fake\"}");
        System.out.println("{\"type\":\"turn.started\"}");
        System.out.println("{\"type\":\"item.completed\",\"item\":{\"id\":\"i0\","
            + "\"type\":\"agent_message\",\"text\":\"args=" + String.join(" ", arguments)
            + " prompt=" + prompt.toString().replace('"', '\'') + "\"}}");
        System.out.println("{\"type\":\"turn.completed\",\"usage\":{}}");
        System.out.flush();
    }

    private FakeCodexMain() {
    }
}
```

- [ ] **Step 2: Написать падающий тест**

Создать `aura-agents/src/test/java/aura/agents/CodexSessionTest.java`:

```java
package aura.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import aura.core.AgentEvent;
import aura.core.EventKind;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CodexSessionTest {

    private static SessionConfig fakeCodexConfig() {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        return new SessionConfig(
            List.of(java, "-cp", classpath, "aura.agents.FakeCodexMain", "exec", "--json"),
            Path.of("."),
            "t-fake");
    }

    @Test
    void firstTurnRunsExecAndProducesEvents() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (CodexSession session = CodexSession.start(fakeCodexConfig(), events::add)) {
            session.send("почини тесты");

            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().anyMatch(e -> e.kind() == EventKind.DONE));

            assertThat(events).extracting(AgentEvent::kind)
                .containsSequence(EventKind.SESSION_START, EventKind.ASSISTANT_TEXT, EventKind.DONE);
            assertThat(events).anySatisfy(e ->
                assertThat(e.summaryHint()).contains("prompt=").contains("почини тесты"));
        }
    }

    @Test
    void secondTurnAddsResumeToTheCommand() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (CodexSession session = CodexSession.start(fakeCodexConfig(), events::add)) {
            session.send("первая");
            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().filter(e -> e.kind() == EventKind.DONE).count() == 1);

            session.send("вторая");
            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().filter(e -> e.kind() == EventKind.DONE).count() == 2);

            List<AgentEvent> texts = events.stream()
                .filter(e -> e.kind() == EventKind.ASSISTANT_TEXT).toList();
            assertThat(texts.get(0).summaryHint()).doesNotContain("resume");
            assertThat(texts.get(1).summaryHint()).contains("resume").contains("--last");
        }
    }

    @Test
    void sessionIsAliveBetweenTurnsEvenThoughNoProcessRuns() throws Exception {
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        try (CodexSession session = CodexSession.start(fakeCodexConfig(), events::add)) {
            // У Codex между репликами процесса нет, но сессия логически жива:
            // иначе супервизор станет пересоздавать её на каждом ходу.
            assertThat(session.alive()).isTrue();
            session.send("что-нибудь");
            await().atMost(Duration.ofSeconds(15)).until(() ->
                events.stream().anyMatch(e -> e.kind() == EventKind.DONE));
            assertThat(session.alive()).isTrue();
        }
    }
}
```

- [ ] **Step 3: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-agents -am test`
Expected: FAIL — `cannot find symbol: class CodexSession`.

- [ ] **Step 4: Написать сессию**

`aura-agents/src/main/java/aura/agents/CodexSession.java`:

```java
package aura.agents;

import aura.core.AgentEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Сессия Codex. У {@code codex exec} нет потокового ввода, поэтому каждая
 * реплика поднимает отдельный процесс: первая — как есть, последующие — с
 * {@code resume --last}. Контекст сохраняется на стороне Codex.
 *
 * <p>Плата за это — холодный старт на каждой реплике. Асимметрия с Claude Code
 * зафиксирована в ADR 0004 и намеренно не спрятана за общим интерфейсом.
 */
public final class CodexSession implements AgentSession {

    private static final Logger log = LoggerFactory.getLogger(CodexSession.class);

    private final SessionConfig config;
    private final Consumer<AgentEvent> sink;
    private volatile boolean closed;
    private volatile boolean started;
    private volatile Process current;

    private CodexSession(SessionConfig config, Consumer<AgentEvent> sink) {
        this.config = config;
        this.sink = sink;
    }

    public static CodexSession start(SessionConfig config, Consumer<AgentEvent> sink) {
        return new CodexSession(config, sink);
    }

    @Override
    public void send(String userText) {
        if (closed) {
            throw new IllegalStateException("сессия Codex закрыта");
        }
        List<String> command = new ArrayList<>(config.command());
        if (started) {
            // exec resume --last: продолжаем ту же нить, а не начинаем новую
            int execIndex = command.indexOf("exec");
            command.add(execIndex < 0 ? command.size() : execIndex + 1, "resume");
            command.add(execIndex < 0 ? command.size() : execIndex + 2, "--last");
        }

        try {
            Process process = new ProcessBuilder(command)
                .directory(config.workingDir().toFile())
                .start();
            current = process;
            started = true;

            try (Writer stdin = new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8)) {
                stdin.write(userText);
            }

            CodexEventParser parser = new CodexEventParser(Clock.systemUTC());
            Thread reader = new Thread(() -> readStream(process, parser), "aura-codex-stdout");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            throw new IllegalStateException("не удалось запустить codex", e);
        }
    }

    private void readStream(Process process, CodexEventParser parser) {
        try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (AgentEvent event : parser.parseLine(line)) {
                    sink.accept(event);
                }
            }
        } catch (Exception e) {
            log.debug("поток codex закрыт: {}", e.toString());
        }
    }

    @Override
    public String sessionId() {
        return config.sessionId();
    }

    /** Живой означает «готов принять реплику», а не «процесс работает». */
    @Override
    public boolean alive() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
        Process process = current;
        if (process != null && process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }
}
```

- [ ] **Step 5: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-agents -am test`
Expected: PASS, три теста `CodexSessionTest`.

- [ ] **Step 6: Закоммитить**

```bash
git add aura-agents/
git commit -m "feat(agents): run Codex turns via exec and exec resume --last"
```

---

## Task 15: Сборка приложения, трей и живая проверка

**Files:**
- Create: `aura-app/src/main/java/aura/app/AuraConfig.java`
- Create: `aura-app/src/main/java/aura/app/TaskDispatcher.java`
- Create: `aura-app/src/main/java/aura/app/TrayApp.java`
- Create: `aura-app/src/main/java/aura/app/Main.java`
- Create: `aura-app/src/main/resources/logback.xml`
- Test: `aura-app/src/test/java/aura/app/TaskDispatcherTest.java`

**Interfaces:**
- Consumes: `ProjectRegistry` (Task 6), `PermissionPolicy` + `ConfirmationProvider` (Task 7, 8), `HookServer` (Task 9), `SettingsFileWriter` (Task 10), `SessionSupervisor` + `ClaudeSession` + `CodexSession` (Task 11, 12, 14)
- Produces:
  - `record AuraConfig(Path claudeExe, Path codexExe, Path projectsFile, Path hookJar, Path javaExe, Path runDir, Duration idleTimeout, Duration confirmTimeout)` с методом `Path socketPath()` и статическими `load(Path yaml)` и `defaults()`
  - `class TaskDispatcher` с `DispatchResult dispatch(String phrase)` и статическим
    `List<String> buildCommand(Project project, AuraConfig config, String sessionId, Path settingsFile)`
  - `sealed interface DispatchResult` с `record Sent(String projectName)` и `record ProjectUnknown()`
  - `class TrayApp`, `class Main`

- [ ] **Step 1: Написать падающий тест сборки команды и маршрутизации**

Создать `aura-app/src/test/java/aura/app/TaskDispatcherTest.java`:

```java
package aura.app;

import static org.assertj.core.api.Assertions.assertThat;

import aura.agents.AgentSession;
import aura.agents.SessionSupervisor;
import aura.core.Agent;
import aura.core.Project;
import aura.core.ProjectRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskDispatcherTest {

    /** Для проверок сборки команды: каталог запуска не используется. */
    private static final AuraConfig CONFIG = new AuraConfig(
        Path.of("claude"), Path.of("codex"),
        Path.of("projects.yaml"), Path.of("C:", "aura", "aura-hook.jar"),
        Path.of("C:", "jdk", "bin", "java.exe"), Path.of("C:", "run"),
        Duration.ofMinutes(15), Duration.ofSeconds(20));

    /**
     * Для проверок отправки: диспетчер пишет файл настроек на диск, поэтому
     * каталог запуска должен быть временным. Тест, создающий C:\run, — это
     * тест, который гадит в системе того, кто его запустил.
     */
    private static AuraConfig configIn(Path runDir) {
        return new AuraConfig(
            Path.of("claude"), Path.of("codex"),
            Path.of("projects.yaml"), runDir.resolve("aura-hook.jar"),
            Path.of("C:", "jdk", "bin", "java.exe"), runDir,
            Duration.ofMinutes(15), Duration.ofSeconds(20));
    }

    private static Project project(String name, Agent agent, String... aliases) {
        return new Project(name, List.of(aliases), Path.of("C:", "work", name), agent,
            List.of(), Set.of("Read"), Set.of("Bash"), Set.of());
    }

    private static final class RecordingSession implements AgentSession {
        final List<String> sent = new ArrayList<>();
        @Override public void send(String userText) { sent.add(userText); }
        @Override public String sessionId() { return "s"; }
        @Override public boolean alive() { return true; }
        @Override public void close() { }
    }

    @Test
    void claudeCommandStreamsBothWaysAndCarriesOurSettings() {
        List<String> command = TaskDispatcher.buildCommand(
            project("backend", Agent.CLAUDE), CONFIG, "11111111-2222-3333-4444-555555555555",
            Path.of("C:", "run", "settings.json"));

        assertThat(command).containsSubsequence("-p", "--input-format", "stream-json");
        assertThat(command).containsSubsequence("--output-format", "stream-json");
        assertThat(command).contains("--verbose");
        assertThat(command).containsSubsequence("--session-id", "11111111-2222-3333-4444-555555555555");
        assertThat(command).containsSubsequence("--settings", "C:\\run\\settings.json");
        assertThat(command).containsSubsequence("--add-dir", "C:\\work\\backend");
    }

    @Test
    void claudeCommandNeverBypassesPermissions() {
        List<String> command = TaskDispatcher.buildCommand(
            project("backend", Agent.CLAUDE), CONFIG, "s", Path.of("s.json"));
        assertThat(command).noneMatch(arg -> arg.contains("bypassPermissions"));
        assertThat(command).doesNotContain("--dangerously-skip-permissions");
    }

    @Test
    void codexCommandRunsSandboxedInsideTheProject() {
        List<String> command = TaskDispatcher.buildCommand(
            project("scratch", Agent.CODEX), CONFIG, "s", Path.of("s.json"));

        assertThat(command).containsSubsequence("exec", "--json");
        assertThat(command).containsSubsequence("--sandbox", "workspace-write");
        assertThat(command).containsSubsequence("-C", "C:\\work\\scratch");
        assertThat(command).doesNotContain("--dangerously-bypass-approvals-and-sandbox");
    }

    private static ProjectRegistry twoProjects() {
        return new ProjectRegistry(List.of(
            project("backend", Agent.CLAUDE, "бэкенд"),
            project("frontend", Agent.CLAUDE, "фронтенд")));
    }

    @Test
    void phraseNamingAProjectReachesThatProjectsSession(@TempDir Path tmp) {
        RecordingSession session = new RecordingSession();
        var supervisor = new SessionSupervisor((cfg, sink) -> session,
            Duration.ofMinutes(10), Clock.systemUTC());

        var dispatcher = new TaskDispatcher(twoProjects(), supervisor, configIn(tmp), e -> {});
        var result = dispatcher.dispatch("в проекте бэкенд почини падающие тесты");

        assertThat(result).isInstanceOf(TaskDispatcher.Sent.class);
        assertThat(((TaskDispatcher.Sent) result).projectName()).isEqualTo("backend");
        assertThat(session.sent).containsExactly("в проекте бэкенд почини падающие тесты");
        supervisor.close();
    }

    @Test
    void dispatchWritesTheSettingsFileTheCommandPointsAt(@TempDir Path tmp) throws Exception {
        // Без этого агент получает --settings на несуществующий файл и остаётся
        // без хука: разрешения перестают спрашиваться, и никто этого не замечает.
        var supervisor = new SessionSupervisor((cfg, sink) -> new RecordingSession(),
            Duration.ofMinutes(10), Clock.systemUTC());
        AuraConfig config = configIn(tmp);

        var dispatcher = new TaskDispatcher(twoProjects(), supervisor, config, e -> {});
        dispatcher.dispatch("в проекте бэкенд почини тесты");

        Path settings = tmp.resolve("backend-settings.json");
        assertThat(settings).exists();
        String content = java.nio.file.Files.readString(settings,
            java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content)
            .contains("PreToolUse")
            .contains(config.socketPath().toString());
        supervisor.close();
    }

    @Test
    void phraseWithoutProjectFallsBackToTheLastActiveOne(@TempDir Path tmp) {
        RecordingSession session = new RecordingSession();
        var supervisor = new SessionSupervisor((cfg, sink) -> session,
            Duration.ofMinutes(10), Clock.systemUTC());

        var dispatcher = new TaskDispatcher(twoProjects(), supervisor, configIn(tmp), e -> {});
        dispatcher.dispatch("в проекте бэкенд почини тесты");
        var result = dispatcher.dispatch("а теперь прогони линтер");

        assertThat(((TaskDispatcher.Sent) result).projectName()).isEqualTo("backend");
        assertThat(session.sent).hasSize(2);
        supervisor.close();
    }

    @Test
    void unnamedProjectWithoutHistoryIsReportedRatherThanGuessed(@TempDir Path tmp) {
        var supervisor = new SessionSupervisor((cfg, sink) -> new RecordingSession(),
            Duration.ofMinutes(10), Clock.systemUTC());

        var dispatcher = new TaskDispatcher(twoProjects(), supervisor, configIn(tmp), e -> {});
        var result = dispatcher.dispatch("почини падающие тесты");

        assertThat(result).isInstanceOf(TaskDispatcher.ProjectUnknown.class);
        supervisor.close();
    }
}
```

- [ ] **Step 2: Запустить и убедиться, что падает**

Run: `mvn -q -pl aura-app -am test`
Expected: FAIL — `cannot find symbol: class AuraConfig`.

- [ ] **Step 3: Написать конфигурацию**

`aura-app/src/main/java/aura/app/AuraConfig.java`:

```java
package aura.app;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Настройки приложения. В M1 их немного: где лежат исполняемые файлы, где
 * реестр проектов и какие тайм-ауты.
 */
public record AuraConfig(
    Path claudeExe,
    Path codexExe,
    Path projectsFile,
    Path hookJar,
    Path javaExe,
    Path runDir,
    Duration idleTimeout,
    Duration confirmTimeout
) {

    /**
     * Единственный сокет, через который хуки говорят с приложением. Путь
     * вычисляется в одном месте: {@code Main} слушает именно его, а
     * {@code TaskDispatcher} именно его записывает в файл настроек агента.
     */
    public Path socketPath() {
        return runDir.resolve("aura.sock");
    }

    public static AuraConfig defaults() {
        Path appData = Path.of(System.getenv().getOrDefault("APPDATA",
            System.getProperty("user.home")), "Aura");
        Path localAppData = Path.of(System.getenv().getOrDefault("LOCALAPPDATA",
            System.getProperty("user.home")), "Aura");
        return new AuraConfig(
            Path.of("claude"),
            Path.of("codex"),
            appData.resolve("projects.yaml"),
            Path.of("aura", "aura-hook", "target", "aura-hook.jar").toAbsolutePath(),
            Path.of(System.getProperty("java.home"), "bin", "java.exe"),
            localAppData.resolve("run"),
            Duration.ofMinutes(15),
            Duration.ofSeconds(20));
    }

    public static AuraConfig load(Path yamlFile) {
        AuraConfig defaults = defaults();
        if (!Files.isRegularFile(yamlFile)) {
            return defaults;
        }
        try (InputStream in = Files.newInputStream(yamlFile)) {
            Map<String, Object> root = new Yaml().load(in);
            if (root == null) {
                return defaults;
            }
            return new AuraConfig(
                path(root, "claudeExe", defaults.claudeExe()),
                path(root, "codexExe", defaults.codexExe()),
                path(root, "projectsFile", defaults.projectsFile()),
                path(root, "hookJar", defaults.hookJar()),
                path(root, "javaExe", defaults.javaExe()),
                path(root, "runDir", defaults.runDir()),
                seconds(root, "idleTimeoutSec", defaults.idleTimeout()),
                seconds(root, "confirmTimeoutSec", defaults.confirmTimeout()));
        } catch (Exception e) {
            throw new IllegalStateException("не удалось прочитать конфигурацию: " + yamlFile, e);
        }
    }

    private static Path path(Map<String, Object> root, String key, Path fallback) {
        Object value = root.get(key);
        return value == null ? fallback : Path.of(String.valueOf(value));
    }

    private static Duration seconds(Map<String, Object> root, String key, Duration fallback) {
        Object value = root.get(key);
        return value == null ? fallback : Duration.ofSeconds(Long.parseLong(String.valueOf(value)));
    }
}
```

- [ ] **Step 4: Написать диспетчер**

`aura-app/src/main/java/aura/app/TaskDispatcher.java`:

```java
package aura.app;

import aura.agents.AgentSession;
import aura.agents.SessionConfig;
import aura.agents.SessionSupervisor;
import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.Project;
import aura.core.ProjectRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Превращает фразу в отправленную агенту задачу: определяет проект, собирает
 * команду запуска, берёт у супервизора сессию и пишет в неё реплику.
 *
 * <p>Проект никогда не угадывается. Если фраза не называет проект и активного
 * ещё не было, диспетчер честно сообщает об этом, а не выбирает первый попавшийся.
 */
public final class TaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatcher.class);

    public sealed interface DispatchResult permits Sent, ProjectUnknown {}

    public record Sent(String projectName) implements DispatchResult {}

    public record ProjectUnknown() implements DispatchResult {}

    private final ProjectRegistry registry;
    private final SessionSupervisor supervisor;
    private final AuraConfig config;
    private final Consumer<AgentEvent> sink;
    private volatile String lastProjectName;

    public TaskDispatcher(ProjectRegistry registry, SessionSupervisor supervisor,
                          AuraConfig config, Consumer<AgentEvent> sink) {
        this.registry = registry;
        this.supervisor = supervisor;
        this.config = config;
        this.sink = sink;
    }

    public DispatchResult dispatch(String phrase) {
        Optional<Project> named = registry.resolveFromSpeech(phrase);
        Optional<Project> target = named.isPresent()
            ? named
            : Optional.ofNullable(lastProjectName).flatMap(registry::byName);

        if (target.isEmpty()) {
            return new ProjectUnknown();
        }

        Project project = target.get();
        String sessionId = UUID.nameUUIDFromBytes(
            project.name().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        Path settings = config.runDir().resolve(project.name() + "-settings.json");

        // Файл настроек пишется перед каждым запуском, а не один раз при
        // установке: пути к сокету и к jar меняются вместе с конфигурацией, а
        // агент, получивший --settings на несуществующий файл, просто останется
        // без хука — молча и без единой ошибки.
        SettingsFileWriter.write(settings, config.socketPath(),
            config.hookJar(), config.javaExe());

        SessionConfig sessionConfig = new SessionConfig(
            buildCommand(project, config, sessionId, settings), project.path(), sessionId);

        AgentSession session = supervisor.sessionFor(project.name(), sessionConfig, sink);
        session.send(phrase);
        lastProjectName = project.name();
        log.info("задача отправлена в проект {}", project.name());
        return new Sent(project.name());
    }

    /** Собирает аргументы запуска. Вынесено статикой ради прямой проверки тестом. */
    public static List<String> buildCommand(Project project, AuraConfig config,
                                            String sessionId, Path settingsFile) {
        List<String> command = new ArrayList<>();
        if (project.agent() == Agent.CLAUDE) {
            command.add(config.claudeExe().toString());
            command.add("-p");
            command.add("--input-format");
            command.add("stream-json");
            command.add("--output-format");
            command.add("stream-json");
            command.add("--verbose");
            command.add("--include-partial-messages");
            command.add("--session-id");
            command.add(sessionId);
            command.add("--settings");
            command.add(settingsFile.toString());
            command.add("--add-dir");
            command.add(project.path().toString());
            for (Path extra : project.addDirs()) {
                command.add("--add-dir");
                command.add(extra.toString());
            }
        } else {
            command.add(config.codexExe().toString());
            command.add("exec");
            command.add("--json");
            command.add("--sandbox");
            command.add("workspace-write");
            command.add("-C");
            command.add(project.path().toString());
            command.add("--skip-git-repo-check");
            for (Path extra : project.addDirs()) {
                command.add("--add-dir");
                command.add(extra.toString());
            }
        }
        return List.copyOf(command);
    }
}
```

- [ ] **Step 5: Запустить тесты и убедиться, что проходят**

Run: `mvn -q -pl aura-app -am test`
Expected: PASS, семь тестов `TaskDispatcherTest`.

- [ ] **Step 6: Написать трей и точку входа**

`aura-app/src/main/java/aura/app/TrayApp.java`:

```java
package aura.app;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Иконка в трее: ввод задачи текстом, остановка, выход.
 *
 * <p>В M1 это единственный способ поставить задачу. В M2 к тому же диспетчеру
 * подключится голос, и трей останется запасным вводом.
 */
public final class TrayApp {

    private final TrayIcon icon;

    public TrayApp(Consumer<String> onTask, Runnable onStop, Runnable onExit) throws AWTException {
        if (!SystemTray.isSupported()) {
            throw new IllegalStateException("системный трей недоступен");
        }

        PopupMenu menu = new PopupMenu();

        MenuItem newTask = new MenuItem("Новая задача…");
        newTask.addActionListener(e -> SwingUtilities.invokeLater(() -> {
            String phrase = JOptionPane.showInputDialog(null,
                "Что сделать? Назовите проект в первой фразе.",
                "Aura — новая задача", JOptionPane.QUESTION_MESSAGE);
            if (phrase != null && !phrase.isBlank()) {
                onTask.accept(phrase.trim());
            }
        }));

        MenuItem stop = new MenuItem("Остановить агента");
        stop.addActionListener(e -> onStop.run());

        MenuItem exit = new MenuItem("Выход");
        exit.addActionListener(e -> onExit.run());

        menu.add(newTask);
        menu.add(stop);
        menu.addSeparator();
        menu.add(exit);

        icon = new TrayIcon(placeholderIcon(), "Aura", menu);
        icon.setImageAutoSize(true);
        SystemTray.getSystemTray().add(icon);
    }

    public void status(String text) {
        icon.setToolTip("Aura — " + text);
    }

    public void notice(String text) {
        icon.displayMessage("Aura", text, TrayIcon.MessageType.INFO);
    }

    public void remove() {
        SystemTray.getSystemTray().remove(icon);
    }

    /** Настоящая иконка появится вместе с интерфейсом; здесь достаточно квадрата. */
    private static Image placeholderIcon() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        g.setColor(new java.awt.Color(0x4C, 0x8B, 0xF5));
        g.fillOval(1, 1, 14, 14);
        g.dispose();
        return image;
    }
}
```

`aura-app/src/main/java/aura/app/Main.java`:

```java
package aura.app;

import aura.agents.ClaudeSession;
import aura.agents.CodexSession;
import aura.agents.SessionSupervisor;
import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.Project;
import aura.core.ProjectRegistry;
import aura.ipc.HookResponse;
import aura.ipc.HookServer;
import aura.policy.ConfirmationProvider;
import aura.policy.Decision;
import aura.policy.PermissionPolicy;
import aura.policy.ToolRequest;
import aura.policy.TrayConfirmationProvider;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Точка входа: поднимает сервер хуков, супервизор и трей, связывает их. */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Path configFile = Path.of(System.getenv().getOrDefault("APPDATA",
            System.getProperty("user.home")), "Aura", "config.yaml");
        AuraConfig config = AuraConfig.load(configFile);
        ProjectRegistry registry = ProjectRegistryLoader.load(config.projectsFile());
        log.info("проектов в реестре: {}", registry.all().size());

        SessionSupervisor supervisor = new SessionSupervisor((sessionConfig, sink) -> {
            boolean codex = sessionConfig.command().stream().anyMatch("exec"::equals);
            return codex
                ? CodexSession.start(sessionConfig, sink)
                : ClaudeSession.start(sessionConfig, sink);
        }, config.idleTimeout(), Clock.systemUTC());

        ConfirmationProvider confirmation =
            ConfirmationProvider.guarded(new TrayConfirmationProvider());

        HookServer hookServer = new HookServer(
            config.socketPath(),
            request -> {
                // Самое длинное совпадение, а не первое: если в реестре есть и
                // C:\work, и C:\work\backend, применить надо политику backend.
                Path cwd = Path.of(request.cwd()).normalize();
                Optional<Project> project = registry.all().stream()
                    .filter(p -> cwd.startsWith(p.path().normalize()))
                    .max(java.util.Comparator.comparingInt(
                        p -> p.path().normalize().toString().length()));
                if (project.isEmpty()) {
                    return HookResponse.deny("каталог вне реестра проектов Aura");
                }
                ToolRequest toolRequest = new ToolRequest(
                    request.toolName(), request.toolInputJson(), Path.of(request.cwd()));
                Decision decision = new PermissionPolicy(project.get()).decide(toolRequest);
                if (decision == Decision.ALLOW) {
                    return new HookResponse("allow", "разрешено политикой проекта");
                }
                if (decision == Decision.DENY) {
                    return HookResponse.deny("запрещено политикой проекта");
                }
                Decision answer = confirmation.confirm(toolRequest, config.confirmTimeout());
                return answer == Decision.ALLOW
                    ? new HookResponse("allow", "подтверждено пользователем")
                    : HookResponse.deny("пользователь не подтвердил");
            });
        hookServer.start();

        TrayApp[] tray = new TrayApp[1];
        java.util.function.Consumer<AgentEvent> sink = event -> {
            log.info("[{}] {} {} {}", event.agent(), event.kind(), event.toolClass(), event.target());
            if (tray[0] != null) {
                tray[0].status(event.kind() + " " + event.target());
            }
        };

        TaskDispatcher dispatcher = new TaskDispatcher(registry, supervisor, config, sink);

        tray[0] = new TrayApp(
            phrase -> {
                var result = dispatcher.dispatch(phrase);
                if (result instanceof TaskDispatcher.ProjectUnknown) {
                    tray[0].notice("Не понял, в каком проекте. Назовите проект в фразе.");
                } else if (result instanceof TaskDispatcher.Sent sent) {
                    tray[0].notice("Отправлено в проект " + sent.projectName());
                }
            },
            supervisor::close,
            () -> {
                supervisor.close();
                hookServer.close();
                tray[0].remove();
                System.exit(0);
            });

        tray[0].status("готова");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            supervisor.close();
            hookServer.close();
        }));
        log.info("Aura запущена, сокет хуков: {}", hookServer.socketPath());
        Thread.currentThread().join();
    }

    private Main() {
    }
}
```

`aura-app/src/main/resources/logback.xml`:

```xml
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{HH:mm:ss.SSS} %-5level %logger{20} - %msg%n</pattern>
      <charset>UTF-8</charset>
    </encoder>
  </appender>
  <root level="INFO">
    <appender-ref ref="CONSOLE"/>
  </root>
</configuration>
```

- [ ] **Step 7: Собрать всё и убедиться, что тесты зелёные**

Run: `mvn -q clean package`
Expected: BUILD SUCCESS, все тесты проходят, собраны `aura-hook/target/aura-hook.jar`
и `aura-app/target/aura-app.jar`.

Прописать реальный путь к `aura-hook.jar` в `%APPDATA%\Aura\config.yaml`:

```yaml
hookJar: C:\Aura\aura\aura-hook\target\aura-hook.jar
```

- [ ] **Step 8: Ручная проверка приложения**

Создать `%APPDATA%\Aura\projects.yaml` с одним настоящим проектом:

```yaml
projects:
  - name: sandbox
    aliases: ["песочница", "sandbox"]
    path: C:\Aura\aura\testdata\sandbox
    agent: claude
    allow: ["Read", "Grep", "Glob"]
    confirm: ["Bash", "Write", "Edit"]
    deny: ["WebSearch"]
```

Создать каталог `C:\Aura\testdata\sandbox` с одним файлом внутри, затем запустить:

```bash
mkdir -p /c/Aura/aura/testdata/sandbox && echo "hello" > /c/Aura/aura/testdata/sandbox/readme.txt
java -jar aura-app/target/aura-app.jar
```

Проверить по очереди:

1. Иконка появилась в трее, подсказка «Aura — готова».
2. «Новая задача…» → ввести `в проекте песочница прочитай readme.txt и скажи, что там` → в логе идёт поток нормализованных событий: `SESSION_START`, `TOOL_START READ`, `TOOL_END`, `DONE`.
3. «Новая задача…» → ввести `почини тесты` без названия проекта, но после первой задачи → уходит в `sandbox` (последний активный).
4. «Новая задача…» → ввести `в проекте песочница выполни команду dir` → **открывается окно подтверждения**. Нажать «Нет» → агент сообщает об отказе.
5. Повторить и нажать «Да» → команда исполняется.
6. Повторить и **ничего не нажимать 20 секунд** → окно закрывается само, вызов отклонён.

- [ ] **Step 9: Проверить сквозную цепочку на настоящем агенте**

RISK-6 уже закрыт в Task 10 скриптом-заглушкой: формат ответа хука известен. Здесь
проверяется другое — что вся цепочка собрана верно: сгенерированный файл настроек,
наш `aura-hook`, сокет, политика проекта и окно подтверждения работают вместе.

```bash
cd /c/Aura/aura/testdata/sandbox
echo 'Run the bash command: dir' | claude -p --output-format stream-json --verbose \
  --settings "$LOCALAPPDATA/Aura/run/sandbox-settings.json" > /tmp/hook-check.jsonl
python -c "
import json
for l in open('/tmp/hook-check.jsonl', encoding='utf-8'):
    o = json.loads(l)
    if o.get('type') == 'result':
        print('permission_denials:', json.dumps(o.get('permission_denials'), ensure_ascii=False))
"
```

Ожидается: при нажатии «Нет» в окне подтверждения массив `permission_denials`
непуст и содержит запись про `Bash`, команда не исполнена.

Если массив пуст, а команда исполнилась — сломано звено между Task 10 и Task 15,
а не контракт: смотреть по порядку, что дошло до `aura-hook` (аргумент с путём к
сокету), что дошло до сокета (лог `HookServer`), какой вердикт вернула политика.

- [ ] **Step 10: Закоммитить**

```bash
git add -A
git commit -m "feat(app): wire tray, dispatcher and permission hook into a running M1"
```

---

## Готовность M1

Этап считается сделанным, когда:

1. `mvn clean package` зелёный, все тесты проходят.
2. Задача, введённая текстом в трее, доходит до настоящего агента в настоящем проекте.
3. В логе виден поток канонических событий, а не сырой JSON обоих CLI.
4. Опасный вызов поднимает окно подтверждения; отказ и таймаут не пускают вызов.
5. Каталог вне реестра проектов не запускается ни при каких условиях.
6. RISK-6 закрыт в Task 10 и отмечен в `docs/RISKS.md`; сквозная цепочка подтверждена в Task 15.

## Что осознанно не сделано в M1

Очевидное: звук, модели, нарратор, голосовое подтверждение, стоп-слово,
энергетический профиль. Заглушка сайдкара существует только чтобы зафиксировать
протокол.

Менее очевидное — два пункта дизайна, которые сюда сознательно не попали:

- **Конечный автомат из §12 дизайна.** В M1 состояний нет: задача приходит из
  диалога, а не из каскада микрофона, и машине нечего описывать. Автомат появится
  в M2 вместе с `SENSING`, `WAKE_SCAN`, `CAPTURING`. Писать его сейчас — значит
  писать его дважды.
- **Перезапуск с экспоненциальной задержкой и состояние `DEGRADED` из §16.**
  В M1 супервизор пересоздаёт мёртвую сессию по первому обращению, и этого
  достаточно: единственный внешний процесс — сам агент, его падение видно
  пользователю сразу. Задержка и `DEGRADED` нужны, когда появится сайдкар,
  который может падать по кругу молча, — то есть в M2.

Оба пункта — решения, а не пропуски; если исполнитель наткнётся на них по ходу,
переспрашивать не нужно.
