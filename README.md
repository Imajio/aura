# Aura

Голосовой фронтенд к Claude Code и Codex. Спит в трее, просыпается на кодовое слово,
принимает задачу голосом, ставит её агенту, озвучивает ход работы и итог.

Всё исполняется локально: распознавание и верификация диктора на NPU, языковая модель
озвучки и синтез речи на встроенной графике. Аудио не покидает машину.

**Статус:** проектирование завершено, реализация не начата.

## Документы

| Документ | О чём |
|---|---|
| [Требования](../docs/product-requirements.md) | зачем это, для кого, границы, нефункциональные требования, критерии приёмки |
| [Архитектура](../docs/architecture.md) | компоненты, протоколы, модели, отказы, тесты, порядок реализации |
| [Реестр рисков](../docs/risk-register.md) | открытые риски и эксперименты, которые их закрывают |
| [ADR 0001](../docs/adr/0001-execution-device-per-stage.md) | какая стадия на каком устройстве исполняется |
| [ADR 0002](../docs/adr/0002-narration-trigger-policy.md) | когда приложение открывает рот |
| [ADR 0003](../docs/adr/0003-tool-permissions-and-voice-confirmation.md) | разрешения и голосовое подтверждение |
| [ADR 0004](../docs/adr/0004-agent-session-topology.md) | как держится сессия агента |
| [План M1](../docs/plans/m1-skeleton-and-events.md) | первый этап, по шагам |
| [CONTRIBUTING](CONTRIBUTING.md) | ветки, коммиты, тесты, что не коммитить |

## Целевая платформа

Windows 11, Intel Core Ultra с NPU, встроенная графика Arc. Проверено на
Core Ultra 9 285H: NPU архитектуры 3720, Arc 140T, OpenVINO 2026.3.

## Раскладка

```
pom.xml         родительский POM, Java 21, Maven multi-module
aura-*\         модули оркестрации
sidecar\        Python, OpenVINO — звук и все модели
testdata\       потоки событий, снятые с живых claude и codex
docs\           требования, дизайн, решения, риски
```

## Сборка

```bash
mvn clean package        # всё, включая тесты
mvn -q -pl aura-core test
java -jar aura-app/target/aura-app.jar
```

Работа над кодом ведётся по правилам из [CONTRIBUTING.md](CONTRIBUTING.md).
