# Аудит и результат доработки Allure

## Подтверждённые исходные проблемы

- Постоянные module/target/allure-results смешивали разные запуски; относительный путь TestNG зависел от рабочей директории.
- Скрипт объединял последние API/UI-наборы без общего Run ID; старые архивы лежали в target.
- Описания 56 методов были шаблонными; многие assertions не имели отдельных результатов в Allure.
- Скриншоты сохраняли страницу без маскирования, отсутствие драйвера не объяснялось.
- Защита параметров уже существовала; вместо второго независимого механизма она перенесена в общую реализацию ReportingListener.
- Allure Maven Plugin отсутствовал; теперь подключён root-only без привязки тестовых фаз.
- Старые TestNG-конфигурации выбирали разные методы/классы и не всегда имели обязательные переменные host/port. Исторические ошибки включали отсутствие настроек, создание Chrome и очистку. Эти смешанные результаты не доказывают причину конкретного All Tests.

## Файлы этой доработки

В рабочем дереве до начала уже были десятки изменений. Список ниже относится к реализации Allure, а полный git diff включает и прежнюю работу пользователя.

- pom.xml; api-tests/pom.xml; ui-tests/pom.xml.
- .gitignore; README.md; прежний Windows workflow (исторический файл в Git на `8c98006`).
- scripts/Invoke-TestMaven.ps1; scripts/Get-AllureReport.ps1.
- scripts/checks/run-reporting-probes.py; scripts/checks/Test-ReportPipeline.ps1.
- reporting/src/main/java/ru/tarkovskiy/reporting: RunAgent.java, ReportSafety.java, ReportingListener.java, Checks.java, ReportingProbe.java.
- api-tests/src/test/java/ru/tarkovskiy/api/reporting: AllureParameterGuard.java, SafeAllureHttpFilter.java, новый ApiChecks.java.
- ui-tests/src/test/java/ru/tarkovskiy/ui/reporting: AllureUiListener.java, UiFailureDiagnostics.java, новые SafeScreenshot.java и UiReportingProbe.java.
- BaseApiTest.java; BaseUiTest.java.
- API tests: DbConnectionTest.java, LoginTest.java, RegistrationTest.java, TaskApiTest.java.
- UI tests: LoginUiTest.java, RegistrationUiTest.java, TaskUiTest.java.
- allure.properties обоих модулей.
- Локальный игнорируемый .idea/workspace.xml: VM options существующих TestNG-конфигураций.
- docs/allure-reporting.md; этот документ.

Защита validateTarget, требования переменных окружения, SQL и бизнес-ожидания не менялись этой доработкой. Скрипты секретов и существующая документация сохранены. Старые результаты не перемещались и не удалялись.

## Проверки

- Java 17, Maven offline test-compile -DskipTests: BUILD SUCCESS для родителя и обоих модулей после последних Java-изменений.
- Автономные ReportingProbe: TestNG + AspectJ @Step + Allure.step, ошибки сценария/очистки, скрытие синтетических секретов, параметры DataProvider, PNG и дедупликация на имитации WebDriver.
- Два Run ID: независимые UUID результатов, общая группировка API/UI и неизменность хешей предыдущего запуска.
- Test-ReportPipeline.ps1 с fake CLI: объединение ровно выбранного запуска, перенос History/Trend, неизменность исходного JSON, просмотр без создания файлов.
- PowerShell syntax: успешно; Python AST syntax: успешно.
- Maven help:describe для allure-maven 2.15.2: BUILD SUCCESS; подтверждены report/serve и параметры.
- git diff --check обнаруживает CRLF как whitespace в существующих XML suites. Массовая нормализация пользовательских файлов не выполнялась.
- Предупреждение компилятора: используется deprecated TestNG.getDefault для выключения стандартных файловых reporters до их установки (защита DataProvider-аргументов).

Проверки не запускали интеграционные API/UI-классы, Chrome, TEST/PROD или JDBC. Maven Central использовался только для недостающих зависимостей сборки и метаданных плагина. Синтетические результаты оставлены на диске.

## Ручные проверки и ограничения

Проверить после Reload Maven Projects отображение Plugins → allure; применённые VM options TestNG; запуск реального браузера и работу маски. Интеграционный прогон требует отдельного согласования. Параллельные providers/forks, retry и изменение порядка DataProvider требуют дополнительной стратегии идентификации вариантов. Строковые фактические значения намеренно скрыты, доступны длина/совпадение. Снимок — обезличенная структура, не читаемый снимок пользовательского содержимого.

History/Trend Allure 2 содержит окно последних запусков, но файлы старых отчётов не удаляются. GitHub retention/cache не заменяет постоянное архивное хранилище. Plugin previews не являются основной цепочкой истории. Команды и настройка: allure-reporting.md.

Предложение commit после просмотра: `feat: isolate Allure runs and add safe diagnostics with history`.
Commit/push/merge и GitHub Actions не выполнялись.

Финальная проверка Surefire после исправления Windows-инициализации: BUILD SUCCESS, по 2 синтетических варианта в каждом модуле, 0 ошибок/пропусков. Оба модуля записали Run ID `20261107T134606050570100Z-ffca02a5-96b9-423a-8c05-181ce784daee`. Ранний stdout агента перенесён в onExecutionStart: предупреждение corrupted channel устранено. Корневая validate создаёт UUID до запуска модулей, не полагаясь на обнаружение родительского процесса.

Остались предупреждения зависимостей: SLF4J без binding, deprecated disableXmlReport/TestNG.getDefault, Ant SecurityManager при in-process запуске bootstrap на JDK 17. Сборка и автономные проверки проходят; перенос на новые JDK требует отдельной проверки Ant.

## Исправление startup агента для IntelliJ TestNG

Подтверждённая причина NoSuchFileException — выбор Maven-сессии, которой нет при прямом TestNG-запуске. В просмотренном старом байткоде чтение .sessions выполняется только для трёх аргументов. Приведённые пользователем два аргумента сами по себе не воспроизводят эту ветку; полный фактический command line на скриншоте свёрнут, поэтому источник третьего аргумента не доказан. Обработан сценарий наследования Surefire argLine IntelliJ и повторного подключения агента.

Bootstrap теперь находится исключительно в reporting/agent/ru/tarkovskiy/reporting/bootstrap/IsolatedRunAgent.java и JAR, вне test classpath. Старый RunAgent.java оставлен как deprecated marker без реализации. POM передаёт явный maven=session-key. Для IntelliJ launcher унаследованная Maven-метка не используется. Повторная инициализация совместимого агента идемпотентна. Действительный Maven с отсутствующим файлом сессии получает объяснение; UUID общего запуска не подменяется случайным fallback.

Проверки: offline test-compile обоих модулей — BUILD SUCCESS. scripts/checks/check-agent-startup.py на Windows Java 17.0.12 — PASS: 4 независимых direct API/UI, 2 старта с общей Maven-сессией, 2 IDEA launcher stub с отсутствующей сессией и дублирующим агентом. В каждом старте загружен AspectJ; bootstrap загружен из JAR. Заглушка launcher не исполняет TestNG. Браузер, HTTP, БД и интеграционные сценарии не запускались. Результаты и history не удалялись; VM options root,module с прежними путями JAR остаются действительными.
