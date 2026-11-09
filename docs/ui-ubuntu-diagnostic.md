# Ручная диагностика UI на Ubuntu 24.04

Workflow: `.github/workflows/ui-ubuntu-diagnostic.yml`, имя **UI Ubuntu diagnostic**.
Единственный trigger — `workflow_dispatch`. Default — **NavigationOnly**.
Job выполняется только для `refs/heads/develop` и использует environment `test`.
Concurrency group `task-manager-test-environment` совпадает с основным Ubuntu workflow:
два workflow не используют TEST одновременно, активный запуск не отменяется.

## Режимы

**NavigationOnly:** standalone Java entry point `UbuntuDiagnosticLauncher`, без
TestNG suite/fixtures, авторизации, пользователей, задач и JDBC.
Сначала вызывается существующий `RenderReadiness`: ожидание до четырёх минут,
HTTP 200 от TEST `/login` и наличие полей username/password. Только после готовности
создаётся настоящий headless Chrome и ровно один раз вызывается существующий
`LoginPage.open(TEST)`. Проверка готовности и NavigationDiagnostics остаются прежними.
Никакие значения в поля не вводятся. Скриншоты в этом режиме не создаются.
DB/password secrets не передаются этому шагу и удаляются из среды дочерней JVM.
HTTP-прогрев не является браузерным повтором. Если TEST не готов, Chrome не создаётся
и driver.get не вызывается. Повторов браузерной навигации и увеличения существующего
15-секундного Selenium-ожидания нет.

**UiSmoke:** программный TestNG использует без изменений `ui-tests/smoke-tests.xml`:
вход, создание, редактирование и удаление задачи. Это реальные операции только
в TEST и обращения к TEST-БД существующих тестов. Перед TestNG выполняется прежний
`DbConnection.validateTarget` без подключения. Дополнительно тот же target проверяет
Python launcher до сборки. Требуются шесть существующих environment test настроек:
TEST_USER_PASSWORD, TEST_DB_URL, TEST_DB_USERNAME, TEST_DB_PASSWORD,
TEST_ALLOWED_DB_HOST, TEST_ALLOWED_DB_PORT. Они доступны только шагу UiSmoke.
Прохождение означает четыре passed, ноль failed/skipped и отсутствие ошибок TestNG.
RenderReadiness и очистка выполняются существующими fixtures. API/Regression не выбираются.

## Linux и браузер

Python 3 launcher не использует PowerShell, DPAPI и Windows Job Object.
Maven `-pl ui-tests -am test-compile` компилирует без запуска тестов; отдельный
dependency goal подготавливает test classpath и AspectJ. Затем запускается Java 17
с существующими IsolatedRunAgent и AspectJ agent, test resources/SPI и общим механизмом
Allure. Shell interpolation и передача секретов аргументами команд не применяются.
При прерывании launcher завершает группу дочернего процесса.

Используется установленный `/usr/bin/google-chrome` образа Ubuntu 24.04. Проверяется
ChromeDriver из PATH. Если MAJOR.MINOR.BUILD не совпадает, в RUNNER_TEMP загружается
соответствующий Linux x64 ChromeDriver из официального Chrome for Testing manifest.
Перехода к несовместимому milestone или скрытой загрузки Selenium Manager нет.
Если подходящий build отсутствует, setup завершается ошибкой. ZIP не распаковывается
целиком: записывается только фиксированный chromedriver binary. Существующий Chrome
runner не обновляется. Driver явно выбирается свойством webdriver.chrome.driver.

Выбранный driver path ведёт через приватный Linux launcher в RUNNER_TEMP:
`os.execve` запускает настоящий драйвер без `TEST_*`, `TM_*` и JVM/Maven overrides.
Секреты остаются в тестовой JVM, но не наследуются ChromeDriver и его Chrome.
Системные PATH/HOME/TMPDIR/XDG/locale/HEADLESS сохраняются; аргументы, версии и код
завершения драйвера передаются без изменений. Это также применяется к NavigationOnly.
Одного `withEnvironment` недостаточно: Selenium 4.27.0 дополняет унаследованную среду.
Автономный UbuntuDriverEnvironmentProbe проверяет настоящие Selenium service/path
и process builder с фиктивными driver/browser, без HTTP polling и реального Chrome.

NavigationOnly задаёт binary явно; UiSmoke использует прежний BaseUiTest с системным
Chrome. Перед каждым бизнес-методом отдельный listener сравнивает фактические
browserVersion/chromedriverVersion с проверенными исполняемыми файлами. Несовпадение
завершает диагностику ошибкой до выполнения бизнес-метода. Sandbox и TLS не ослаблены.

Версии образа Ubuntu и локального Windows Chrome могут различаться. Такой прогон
не доказывает влияние только ОС: фактические версии нужно учитывать при сравнении.

## Результаты и секреты

Уникальный Run ID: `ubuntu-<github.run_id>-<github.run_attempt>`.
Исходные Allure-результаты: `test-runs/<id>/ui-tests/NavigationOnly|Smoke/allure-results`.
Повторное использование существующего Run ID запрещено, старые результаты не удаляются.

Публикуется только `test-runs/<id>/ubuntu-diagnostic/`:

- diagnostics.log — разрешённые поля существующих UI_NAVIGATION/UI_BROWSER_SESSION/
  UI_FAILURE_DIAGNOSTICS, UI_RENDER_READINESS и UI_NAVIGATION_ONLY_STAGE, безопасный итог набора;
- status.json — режим, стадия, код завершения, Ubuntu и выбранные версии браузера/драйвера;
- allure-results/ — проверенная копия результатов;
- allure-html/ — отдельный HTML от Allure CLI 2.46.1;
- completed.marker при завершённом наборе и audited.marker при успешном аудите.

Java диагностирует маршрут/категорию URL, длительность, readyState, DOM, признаки
формы, фактические версии и параметры сессии. HTTP-статус/сетевой код пассивные:
при отсутствии данных остаётся UNKNOWN. Полной сетевой трассы и цепочки URL нет.
Отдельное вложение TEST Render readiness содержит READY/NOT_READY и длительность
HTTP-прогрева. NavigationOnly stage различает RENDER_READINESS_FAILED,
CHROME_START_FAILED, CHROME_NAVIGATION_FAILED и CHROME_CLEANUP_FAILED; успешный итог —
COMPLETE. Эти поля также сохраняются в diagnostics.log и консоли после аудита.

Произвольные stdout/stderr дочерних процессов не печатаются и не загружаются.
Они временно обрабатываются в приватном файле. Консоль содержит только отфильтрованные
диагностические поля после аудита. Raw capabilities, exception messages, URL query,
headers/cookies/input values не экспортируются. Существующий SafeScreenshot не менялся;
Smoke сохраняет только его маскированные изображения. Проверяются секреты в открытом,
URL/XML/JSON/Base64 представлениях до генерации и перед публикацией HTML.

Artifact `ui-ubuntu-<mode>-<run_id>-<attempt>` хранится 14 дней и загружается также
при падении теста. Успешная генерация Allure не скрывает ошибку теста. Ошибка аудита
блокирует artifact. При сбое checkout/setup до launcher диагностического artifact
может не быть; сведения остаются в логе соответствующего setup-шага.
Существующая Allure history не используется и не перезаписывается этим workflow.

## Автономная проверка

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/checks/check-ui-ubuntu-diagnostic.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/checks/check-ui-ubuntu-diagnostic.py --compile-java --jdk-home "/mnt/c/Program Files/Java/jdk17"
```

Нужны PyYAML, JDK и уже сохранённый dependency classpath (можно указать
`--classpath-file`). Загрузок нет. Проверяются YAML/режимы/защита целей, среда,
подбор driver, коды завершения, аудит и artifact guard. Java-компиляция использует
Java 17 API; standalone probe подставляет fake WebDriver и не вызывает TestNG suite,
Chrome, HTTP или JDBC. Сборка и фиктивные вложения живут в отдельном временном каталоге.
Полный GitHub workflow, Maven dependency resolution на чистом runner и реальный Linux
Chrome требуют отдельно согласованного запуска; автономные проверки их не заменяют.

## Ручной запуск

Workflow зарегистрирован в default branch **main**. После согласования запуска
откройте **Actions → UI Ubuntu diagnostic → Run workflow**, выберите **develop**
и режим **NavigationOnly**. Проверьте логи и artifact. **UiSmoke** запускайте
отдельно после завершения текущих TEST jobs. Выбор **main** пропускает diagnostic
job по условию ref.

Официальные источники: [подбор ChromeDriver по build](https://developer.chrome.com/docs/chromedriver/downloads/version-selection),
[требования ручного workflow_dispatch](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow).
