# Ubuntu 24.04 CI: проверка и переход с Windows

Новый workflow: `.github/workflows/tests-ubuntu.yml`, имя **Task Manager Ubuntu test suites**.
Только `develop`, GitHub environment `test`, Java 17. TEST URL задан явно; PROD не поддерживается.
Windows `tests.yml`, диагностический Ubuntu workflow, бизнес-тесты, XML, зависимости,
Selenium timeout, очистка данных и правила скриншотов не изменены.

## Режимы

| Выбор | Последовательность XML | Выполнений | Уникальных тестов |
|---|---|---:|---:|
| Smoke | api-tests/smoke-tests.xml → ui-tests/smoke-tests.xml | 6 + 4 = 10 | 10 |
| Regression | api-tests/regression-tests.xml → ui-tests/regression-tests.xml | 51 + 66 = 117 | 117 |
| All | ApiSmoke → UiSmoke → ApiRegression → UiRegression | 127 | 117 |

Состав проверяется по XML, аннотациям и числу вариантов существующих чистых
Object[][] DataProvider. Fixtures и тестовые методы для подсчёта не вызываются.
Текущие ожидаемые количества зафиксированы: 6/4/51/66. При изменении состава нужна
явная проверка и обновление ожидаемого количества, иначе запуск завершится ошибкой.
После TestNG сравниваются выполнения каждого метода; failure, skip, ошибки/пропуски
конфигурации и неполный набор означают FAILURE.

Один job, без matrix и параллельных наборов. Job удерживает общую группу
`task-manager-test-environment` на протяжении тестов и отчётов. Это та же группа,
что у Windows CI и Ubuntu diagnostic. GitHub concurrency допускает только один
активный и один ожидающий запуск группы; новый ожидающий запуск может вытеснить
старый ожидающий. Запускайте проверки по очереди и дождитесь завершения предыдущей.

## Запуск

Maven выполняет `test-compile`, а не `test`: собирает существующий reporting-agent,
ресурсы и test classpath. Затем отдельная JVM с reporting-agent и существующим
AspectJ запускает нужный TestNG XML через Ubuntu launcher. Сохраняются SPI listeners,
Allure, fixtures и очистка данных. Linux не использует `Invoke-TestMaven.ps1`, DPAPI
или Windows-пути.

Подготовка браузера переиспользует `run-ui-ubuntu-diagnostic.py prepare-browser`:
установленный Google Chrome + ChromeDriver той же MAJOR.MINOR.BUILD; при необходимости
драйвер скачивается только из проверенного Google Chrome for Testing URL.
В UI задаётся явный путь драйвера; перед выполнением тестового метода проверяются
реальные версии браузера/драйвера сессии. UI сохраняет headless flags и Selenium waits.

Перед настоящим ChromeDriver выполняется приватный Linux launcher в RUNNER_TEMP.
Он вызывает [os.execve](https://docs.python.org/3.12/library/os.html#os.execve)
с окружением без всех `TEST_*`, `TM_*` и JVM/Maven overrides. Поэтому драйвер и
запускаемый им Chrome не наследуют TEST-пароли/JDBC-настройки. PATH, HOME, TMPDIR,
XDG, locale, HEADLESS и прочие системные настройки сохраняются. JVM тестов получает
прежние необходимые секреты. В Selenium 4.27.0 `withEnvironment` дополняет исходную
среду, поэтому одной отфильтрованной Map для удаления переменных недостаточно.
Launcher применяется ко всем Ubuntu UI-сессиям через существующий явный выбор
драйвера, включая NavigationOnly, UiSmoke и UiRegression; Windows/local не меняются.

Перед каждым XML выполняется HTTP readiness только фиксированного TEST `/login`:
до 4 минут, без перенаправлений и credentials, HTTP 200 + поля username/password.
Тело ответа, headers и текст сетевых исключений не публикуются. Это отдельная
предварительная проверка, а не повтор теста или browser navigation. UI дополнительно
сохраняет существующий `RenderReadiness` fixture, как и Windows CI.
`NOT_READY` фиксируется на стадии readiness; Java suite при этом не запускается.

Нормальная ошибка набора не препятствует следующим выбранным наборам и отчётам.
Ошибка аудита прекращает публикацию всего запуска. Повторных тестов нет.

## Настройки GitHub

Существующий environment **test**, secrets:
`TEST_USER_PASSWORD`, `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD`,
`TEST_ALLOWED_DB_HOST`; environment variable `TEST_ALLOWED_DB_PORT`.
Новые секреты не нужны. JDBC проверяется в Python и существующим Java
`DbConnection.validateTarget`: разрешённые host/port, база `task_manager_test`,
TLS `require` или `verify-full`. Значения не передаются в аргументах процессов.

Repository variable **UBUNTU_CI_ENABLED**: отсутствует/не `true` → автоматических
Ubuntu-тестов нет. `true` → push в develop выбирает **All**, но только после
проверки через GitHub API, что Windows workflow отключён (`disabled_manually`
или `disabled_inactivity`). При активном Windows CI или невозможности проверить
его состояние автоматический Ubuntu запуск останавливается до TEST job.
Ручной workflow_dispatch не зависит от переменной; запуск из main не разрешён.
Workflow использует `contents: read`, `actions: read`; изменять настройки GitHub
токеном workflow не разрешено.

Сам gate не занимает общую concurrency-группу: пропущенный автоматический Ubuntu
CI не вытесняет ожидающие Windows/diagnostic запуски.

## Результаты и Allure

Run ID: `ubuntu-ci-<github.run_id>-<github.run_attempt>`.
Исходные результаты: `test-runs/<id>/<module>/<Smoke|Regression>/allure-results`.
Reporting-agent запрещает повторное использование partition. Повтор workflow
создаёт новый run_attempt и новый Run ID, не дописывает предыдущие результаты.

Публикуются только проверенные копии в `test-runs/<id>/ubuntu-ci/`:

- `ubuntu-ApiSmoke-<run>-<attempt>`, `ubuntu-UiSmoke-...`,
  `ubuntu-ApiRegression-...`, `ubuntu-UiRegression-...` — только выбранные наборы,
  raw Allure, безопасные attachments/скриншоты, `suite-summary.json`,
  `completed.marker`, `status.json`, allowlisted `diagnostics.log`; хранение 14 дней.
- `ubuntu-allure-<selection>-<run>-<attempt>` — HTML, точно использованные inputs,
  manifest каждого отчёта и общий status.json; хранение 90 дней.

Smoke/Regression дают по три HTML: `api-tests-<kind>`, `ui-tests-<kind>`,
`Combined-<kind>`. All даёт эти шесть и ещё `api-tests-All`, `ui-tests-All`,
`Combined-All` (всего девять). `Combined-All` содержит 127 выполнений, не 117.
Только в копиях для `*-All` historyId и testCaseId дополняются идентичностью набора, чтобы
пересечение Smoke и Regression не превращалось в Allure retry (включая Allure 2.46).
Добавляется безопасный параметр Execution suite с фиксированным именем набора. Исходные файлы
не меняются. Attachment paths переписываются вместе с копиями файлов, без выхода
за каталог. Manifest показывает число выполнений и уникальных тестов.

Из существующей диагностики сохраняются actual Chrome/ChromeDriver/Selenium,
Java/OS, headless flags, URL categories, timing, DOM/readyState/markers и доступные
HTTP/network признаки. `suite-summary.json` содержит фактическую Java/OS/arch,
expected/actual по методам и config errors. Arbitrary stdout/stderr дочерних процессов
не публикуется: оно захватывается приватно, выводятся только безопасные поля.
Это ограничивает диагностику ошибок сборки: подробный Maven stack trace не является artifact.

Сохранение history/trends изолировано от Windows: `.allure-history-ubuntu`,
Actions cache `allure-ubuntu-<branch>-<run>-<attempt>`. Только history и manifest,
без старых raw results. История выбирается по stream и fingerprint состава тестов;
при изменении состава начинается новый тренд. Run-specific каталоги не перезаписываются.
Cache ограничен обычной политикой GitHub и может быть вытеснен; тогда отчёт создаётся
без старой истории. Аудит применяется к восстановленной истории, inputs и готовому
HTML. На обычном падении тестов artifacts и history сохраняются; при утечке/неудачном
аудите upload блокируется. Отмена/убийство runner может не оставить готовых artifacts.

## Автономная проверка

Без HTTP, Chrome, JDBC и выполнения бизнес-тестов:

```bash
python3 scripts/checks/check-tests-ubuntu.py --compile-java --allure-offline
python3 scripts/checks/check-ui-ubuntu-diagnostic.py --compile-java
python3 scripts/checks/check-ui-navigation.py
```

Требуются Python 3 с PyYAML для проверок YAML, заранее закешированные `target/reporting-probe-classpath.txt` обоих
модулей и jars, JDK с javac, а для `--allure-offline` локальный Allure 2.46.1
в `.allure/allure-2.46.1`. Проверки не загружают отсутствующие зависимости.
В данной WSL-среде используется `--jdk-home '/mnt/c/Program Files/Java/jdk17'`
для Java 17 API compilation. Результат компиляции и синтетические отчёты находятся
только во временном каталоге; target/test-runs не меняются.
Java probe запускает только синтетические TestNG fixtures и чистые DataProvider
для инвентаризации, не реальные тесты/fixtures проекта.

Эти проверки подтверждают конфигурацию, безопасность, выбор наборов, failure paths
и сборку отчётов. Они не доказывают работоспособность приложения/сети/DB/Chrome
на реальном Ubuntu runner и не устанавливают причину Windows DATA_DOCUMENT.

## Публикация, проверка, переключение и rollback

Каждый фактический commit/push, запуск и изменение настроек требует отдельного
согласования владельца. Следующий порядок — инструкция, сейчас не выполняется.

1. Проверить diff и включить только файлы Ubuntu инфраструктуры; исключить старые
   LF/CRLF-изменения, DOCX, target/test-runs. Commit/push develop с `[skip ci]`,
   чтобы публикация не запустила существующий Windows push workflow.
2. В чистом временном detached worktree от актуального origin/main взять
   **только** `.github/workflows/tests-ubuntu.yml` из опубликованного SHA develop.
   Проверить точный blob SHA и diff из одного файла, отдельный commit `[skip ci]`,
   обычный push main без force. Не merge develop → main. Проверить удалённый SHA,
   удалить только созданный чистый worktree. Код launcher в main не нужен:
   ручной запуск выбирает develop, в main test job запрещён условием gate.
3. GitHub → Actions → **Task Manager Ubuntu test suites** → Run workflow →
   Branch **develop**, suite **Smoke**. После завершения проверить expected/actual
   6+4, коды, artifacts, три Allure HTML, диагностику и отсутствие секретов.
4. Так же последовательно запустить **Regression** (51+66), затем **All**
   (6+4+51+66; Combined-All 127/117). Проверить fixtures/cleanup, девять отчётов,
   links attachments/screenshots и следующий тренд, а не только зелёный status.
   При падении исследовать artifacts; не включать automatic CI до разбора.
5. Убедиться, что нет активного Windows/Ubuntu/diagnostic запуска. GitHub Actions →
   **Task Manager test suites** → меню → **Disable workflow**. Файл tests.yml
   остаётся неизменным. Отключение не отменяет уже выполняющиеся runs:
   их нужно дождаться до переключения.
6. Settings → Secrets and variables → Actions → Variables: задать repository
   `UBUNTU_CI_ENABLED=true`. Следующий обычный push develop запустит Ubuntu **All**.
   Проверка состояния Windows fail-closed исключает два автоматических полных CI.
   Не включать Windows обратно, пока переменная включена.
7. Rollback: сначала убрать `UBUNTU_CI_ENABLED`/задать `false`, дождаться завершения
   Ubuntu, затем Actions → Windows workflow → **Enable workflow**. Следующий push
   вернёт прежний Windows CI. Файлы и отчёты не удаляются. При необходимости
   Windows можно запустить вручную только после завершения текущей TEST работы.

Проверенные механизмы GitHub:
[Disable/enable workflow](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows),
[Get workflow state](https://docs.github.com/en/rest/actions/workflows#get-a-workflow).
