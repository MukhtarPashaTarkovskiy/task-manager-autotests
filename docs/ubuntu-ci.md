# Основной CI на Ubuntu 24.04

Workflow `.github/workflows/tests-ubuntu.yml` называется **Task Manager Ubuntu test suites**.
Тестовый job разрешён в `develop` и использует GitHub environment `test`, Java 17 и фиксированный URL TEST.

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
что у UI Ubuntu diagnostic. GitHub concurrency допускает только один
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
выполняет существующий `RenderReadiness` fixture.
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

Для ручного запуска выберите **Actions → Task Manager Ubuntu test suites → Run
workflow**, ветку **develop** и один из режимов **Smoke**, **Regression**, **All**.
Ручной запуск доступен при любом значении repository variable `UBUNTU_CI_ENABLED`.
Push в `develop` выбирает **All**, когда `UBUNTU_CI_ENABLED=true`. Если переменная
отсутствует или сравнение с `true` в GitHub Actions ложно, job пропускается до входа
в общую очередь TEST. Ветка `main` регистрирует workflow для ручного запуска.
Токен workflow имеет только `contents: read`.

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

В **Actions → запуск → Summary → Artifacts** скачайте ZIP нужного набора или общий
архив Allure до окончания срока хранения. Распакуйте ZIP. Для просмотра HTML с
вложениями запустите `allure open <каталог-html-отчёта>`.

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

История Ubuntu хранится в `.allure-history-ubuntu` и восстанавливается через
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

## Переключение CI

Каждый commit, push, запуск и изменение настроек выполняются после отдельного
согласования. Исторические ручные запуски прошли успешно: Smoke 10/10
(`42489706972`), Regression 117/117 (`42490264869`) и All 127/127
(`42491303056`). Автоматический режим проверяется отдельным push после включения.

1. Сверить актуальные `origin/develop` и `origin/main`, default branch, доступ к
   environment `test` и статус `UBUNTU_CI_ENABLED`. До переключения переменная
   должна быть снята или иметь значение `false`. Записать SHA обеих веток,
   содержащие старый workflow, и текущие branch protection и rulesets для обеих
   веток. Проверить required workflows и required status checks, включая правила
   организации, и согласовать замену зависимостей от Windows jobs до публикации.
2. В Actions дождаться завершения всех активных и ожидающих Windows, Ubuntu и
   диагностических запусков. Согласовать окно без других push и ручных запусков;
   предупредить о паузе автоматического CI.
3. Отключить **Task Manager test suites** через Actions. Проверить, что workflow
   имеет статус Disabled и новых или ожидающих Windows jobs нет. При невыполнении
   этой проверки остановить переключение.
4. Опубликовать согласованный патч в `develop`. Включить только файлы миграции,
   исключив прежние LF/CRLF-изменения и локальные материалы. Проверить, что старый
   YAML отсутствует и push пропустил Ubuntu TEST job при выключенной переменной.
5. В чистой копии от актуального `origin/main` взять из опубликованного SHA
   `develop` только `.github/workflows/tests-ubuntu.yml` и удалить старый Windows
   YAML. Проверить идентичность blob Ubuntu и diff ровно из двух файлов.
   Опубликовать отдельный commit в `main` обычным push по действующим правилам
   защиты. Проверить default branch и наличие **Run workflow** с выбором develop.
6. Установить repository variable `UBUNTU_CI_ENABLED=true`. Сделать отдельный
   согласованный безопасный push в `develop`, например пустой commit без
   `[skip ci]`. Сверить событие `push` и SHA в Actions. Дождаться All и проверить
   **127/127 PASS** по четырём наборам, девять HTML-отчётов, вложения и историю.
   Сверить отсутствие Windows-запусков для этого SHA.
7. Сверить фактический контекст Ubuntu job с required checks и проверить обе ветки.
   Текущий workflow ограничен push в develop и ручным запуском. Привязка этого
   контекста как обязательного на PR или main требует отдельной проверки событий
   и правил. Зафиксировать SHA, run ID, статус переменной, rulesets и SHA отката.

## Откат на Windows CI

1. Снять `UBUNTU_CI_ENABLED` или установить `false` и дождаться завершения всех
   текущих и ожидающих TEST jobs. На время отката согласовать паузу ручных запусков.
2. Восстановить прежний workflow в каждой ветке из записанного SHA этой ветки
   отдельным коммитом. Из корня чистой рабочей копии нужной ветки:

   ```bash
   git restore --source='SHA-до-миграции' -- .github/workflows/tests.yml
   ```

   Подставьте записанный SHA нужной ветки. Для текущих базовых снимков это `8c98006` в develop и `22e4bcb` в main.
   Проверьте diff, опубликуйте восстановление по правилам веток и сверяйте статус
   Windows workflow после появления файла в default branch.
3. Убедиться, что автоматический Ubuntu CI выключен, включить Windows workflow
   в Actions при статусе Disabled и вернуть согласованные правила checks.
   Следующий согласованный push в develop проверит Windows CI. История и архивы
   Ubuntu и локальные отчёты остаются доступными в пределах их сроков хранения.

Документация GitHub о [ручном запуске](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)
и [переключении workflow](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows).
