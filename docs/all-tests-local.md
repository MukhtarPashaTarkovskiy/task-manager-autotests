# Общий локальный запуск All Tests

## Вывод аудита

Текущая конфигурация готова к ручному полному прогону зелёной кнопкой All Tests. Дополнительная подготовка каталогов перед каждым запуском не требуется: корневая validate компилирует reporting-agent, создаёт UUID Maven-сессии и собирает JAR до Surefire. Полный интеграционный прогон в рамках аудита не выполнялся; успешность всех проверок общего запуска ещё должен подтвердить пользователь.

Установленной новой ошибки POM или бизнес-кода не найдено; они не менялись в этой задаче. Изменены только автономные проверки состава тестов и эта инструкция.

## Точные настройки IntelliJ Maven Run Configuration

- Name: существующее `All Tests`.
- Command line: `test -fae`.
- Working directory: `$PROJECT_DIR$` = `D:/skillbox/task-manager-autotests`, где находится родительский pom.xml.
- JRE: Project JDK `17` (проверено .idea/misc.xml); можно явно выбрать установленную JDK `C:/Program Files/Java/jdk17`.
- Skip tests: выключено.
- VM options Maven runner: пусто. Оба `-javaagent` задаёт Surefire в POM для дочерних JVM; вручную в Maven runner их добавлять не нужно.
- Profiles: дополнительные профили не требуются.
- Не добавлять `-pl`, `-Dtest`, `-Dgroups`, `-DexcludedGroups`, `-Dsurefire.suiteXmlFiles`, skipTests/maven.test.skip, `-T` для полного последовательного запуска.
- Environment variables: TEST_USER_PASSWORD, TEST_DB_URL, TEST_DB_USERNAME, TEST_DB_PASSWORD, TEST_ALLOWED_DB_HOST, TEST_ALLOWED_DB_PORT. Значения оставить текущие: все шесть заполнены и побайтно совпадают с успешными API Tests/UI Tests All. Значения не печатались и не записывались в новые файлы.
- Pass parent environment: текущая настройка включена. Не задавать в родительской среде переиспользуемый TM_RUN_ID/TM_SUITE и не переопределять Java agents через JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS. Это условие чистоты запуска, а не обнаруженная причина прежних ошибок.
- HEADLESS необязателен: без true UI использует обычный Chrome, как текущий локальный TestNG. TEST_BASE_URL необязателен; код по умолчанию допускает только фиксированный TEST origin.

Перед первым запуском после изменения POM достаточно Reload All Maven Projects. Отдельный ручной test-compile, mkdir или запуск PowerShell перед нажатием зелёной кнопки не нужны.

## Сравнение TestNG и Maven

| Условие | TestNG IntelliJ | All Tests Maven |
|---|---|---|
| Состав | API/UI package tests | Стандартный поиск Surefire Test*, *Test, *Tests, *TestCase |
| JDK | Java 17 по предоставленным успешным запускам | Project JDK 17 |
| TEST-переменные | 6 заполненных | Все 6 совпадают |
| Java agents | reporting + AspectJ в VM options | reporting + AspectJ из argLine Surefire |
| Рабочий каталог | IDE default/project | Reactor root; дочерние JVM — каталоги модулей |
| Зависимости | Maven import в IDE | Раздельный test classpath каждого модуля из POM |
| Listeners | ServiceLoader TestNG и Allure | Те же services из test resources; Allure TestNG из зависимости |
| Репортёры TestNG | ReportingListener выключает стандартные файловые reporters | Дополнительно usedefaultlisteners=false в Surefire |
| Порядок | Один выбранный модуль | UI, затем API; без параллельного reactor/forks |
| Run ID | Отдельный запуск | Общий UUID корневой Maven-сессии |

Текущие 7 классов с бизнес-тестами соответствуют стандартным шаблонам Surefire. Вспомогательные BaseUiTest/BaseApiTest не содержат @Test; Probe-классы отчётности не соответствуют шаблонам обычного запуска. @Factory, retryAnalyzer, дополнительные @Listeners и групповые фильтры в проверенных тестах отсутствуют. POM не задаёт suiteXmlFiles: Smoke/Regression XML не исполняются дополнительно к обнаруженным классам. Regression выбирает те же packages, Smoke содержит подмножество. -fae продолжает следующий модуль после ошибки предыдущего, не повторяет тесты.

Тесты не используют относительные файловые пути к схемам: JSON Schema читается с classpath. Переход от project working directory к каталогу модуля не меняет их поиск. UI ChromeOptions определяется HEADLESS одинаково для обоих способов. @BeforeSuite ожидания TEST Render, @BeforeMethod браузера/состояния и @AfterMethod очистки — те же методы, без изменения порядка в коде. Для API создаются новые SessionFilter на метод; общие тестовые пользователи не исполняются одновременно в двух модулях. Cleanup и DbConnection.validateTarget не менялись.

## Проверенный состав

| Класс | Вариантов |
|---|---:|
| DbConnectionTest | 1 |
| LoginTest | 5 |
| RegistrationTest | 17 |
| TaskApiTest | 28 |
| LoginUiTest | 13 |
| RegistrationUiTest | 30 |
| TaskUiTest | 23 |
| Итого | 117 |

Подсчёт выполнялся через отражение аннотаций и вызов только чистых DataProvider, возвращающих массивы. Использован синтетический пароль в отдельном процессе. Методы тестов и fixtures не исполнялись. Все 56 методов enabled, invocationCount=1. Умножение на варианты DataProvider даёт API 51 и UI 66, как в успешных отдельных прогонах пользователя.

## Allure

В автономном Maven `test -fae` с явно выбранным ReportingProbe$Scenario подтверждены оба агента, последовательный reactor и один Run ID:
`20261107T150742549069200Z-8b0c210e-6395-490d-8f07-013c9431ba68`.
В каждом модуле по 2 passed синтетических варианта; completed.marker присутствует. Результаты находятся в test-runs/<run-id>/ui-tests/All и api-tests/All. Этот синтетический запуск не заменяет проверку 117 бизнес-сценариев.

После полного ручного прогона взять Run ID из консоли и сформировать объединённый отчёт:

```powershell
powershell -NoProfile -File scripts/Get-AllureReport.ps1 -RunId RUN_ID -Scope Combined -Kind All -Open
```

Для отдельных отчётов Scope=api-tests или ui-tests. History/Trend формируется существующим скриптом; результаты других запусков не добавляются. Команда генерации не выполняет тесты. Старые результаты не удаляются.

## Исторические проблемы: что известно

Подтверждено ранее: ошибки bootstrap Allure до выполнения тестов, чтение отсутствующей Maven-сессии при прямом TestNG, ранний stdout агента, нарушавший канал Surefire. Эти дефекты исправлены в предыдущих задачах. В старых смешанных результатах встречались отсутствие обязательных TEST-настроек, ошибки создания Chrome и очистки; часть старых TestNG-конфигураций действительно не задавала host/port. Но сейчас текущие три конфигурации имеют одинаковые шесть переменных.

Не доказано для конкретного прошлого общего прогона: влияние холодного старта Render, Chrome/driver mismatch, оставшихся данных, порядка методов или отличий родительской среды. Полного изолированного лога именно того запуска нет. Нельзя объявлять их установленными причинами либо менять из-за них бизнес-ожидания.

## Выполненные проверки

- Offline Java 17 Maven `test -fae -Dtest=ru.tarkovskiy.reporting.ReportingProbe$Scenario`: BUILD SUCCESS; оба модуля скомпилированы; 4 синтетических варианта, 0 failures/errors/skips.
- scripts/checks/check-test-inventory.py с Windows Java 17: API 51, UI 66, PASS; бизнес-методы и fixtures не вызваны.
- JSON текущего синтетического запуска: оба module completed.marker, по 2 passed, один общий Run ID.
- Полный прогон, Chrome, HTTP, JDBC и GitHub Actions не запускались. clean/удаление/commit/push не выполнялись.
- Остаются предупреждения Ant/TestNG/Surefire о deprecated API и отсутствие SLF4J binding; они не мешают проверенному синтетическому reactor.

После ручного прогона ожидается UI 66 + API 51. Проверить общий Run ID, отсутствие skips и итоговый BUILD SUCCESS. До такого прогона подтверждена готовность инфраструктуры, а не результат интеграционных проверок.
