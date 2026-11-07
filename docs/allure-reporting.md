> **Обновление:** основной локальный способ — стандартные кнопки IntelliJ без Run ID. См. [Allure двумя кнопками](allure-clicks.md). Описанные ниже ручные команды сохранены для выбора архивных запусков.

# Allure: запуски, история и IntelliJ

Результаты хранятся в `test-runs/<run-id>/<api-tests|ui-tests>/<Smoke|Regression|All|Selected>/allure-results`.
HTML и копия входных данных: `test-runs/<run-id>/reports/<scope>-<kind>/<report-id>/{html,input}`.
Каждая генерация получает новый report-id. Просмотр существующего HTML ничего не создаёт.
`mvn clean` удаляет сборочные файлы, но не `test-runs`. Старые каталоги результатов оставлены на месте: автоматической миграции, удаления и очистки нет. До самостоятельного применения clean сохраните прежние результаты из target отдельным копированием вне target; смешанные legacy-результаты нельзя считать одним запуском.

## Запуск тестов (только после разрешения использовать TEST)

JDK 17, Maven, Chrome для UI. Требования к TEST-переменным и защищённому хранилищу не изменены. Команды из корня репозитория, Windows PowerShell в отдельном процессе:

```powershell
powershell -NoProfile -File scripts/Invoke-TestMaven.ps1 -Suite ApiRegression
powershell -NoProfile -File scripts/Invoke-TestMaven.ps1 -Suite UiRegression -Headless
powershell -NoProfile -File scripts/Invoke-TestMaven.ps1 -All -Kind All -Headless
powershell -NoProfile -File scripts/Invoke-TestMaven.ps1 -All -Kind Smoke -Headless
powershell -NoProfile -File scripts/Invoke-TestMaven.ps1 -Module api-tests -Test 'LoginTest#shouldLoginWithValidCredentials'
```

Run ID печатается в консоли. Можно явно передать `-RunId` для объединения отдельно выполненных API/UI одного согласованного запуска. Повторная запись той же пары module/suite запрещена; повторная попытка требует нового Run ID. Без явного Run ID создаётся UUID. `-FromEnvironment` предназначен для CI.

Прямой общий Maven-запуск `mvn test -fae` поддерживается. Для одного модуля используйте `mvn -pl api-tests -am test`: корневой validate собирает reporting-agent. Agent до загрузки Allure назначает каталог; корневая фаза validate заранее создаёт UUID сессии, а дочерние Surefire JVM читают его по общей временной метке Maven. Коллизия метки останавливает запуск вместо перезаписи. Параллельные DataProvider/forks и ручные retry не являются поддерживаемым режимом этой схемы; текущие suites последовательные.

## Отчёты и History/Trend

Установите Allure CLI 2.46.1; команда `allure` должна быть в PATH. `RUN_ID` ниже заменяется значением из консоли.

```powershell
powershell -NoProfile -File scripts/Get-AllureReport.ps1 -RunId RUN_ID -Scope api-tests -Kind All
powershell -NoProfile -File scripts/Get-AllureReport.ps1 -RunId RUN_ID -Scope ui-tests -Kind All
powershell -NoProfile -File scripts/Get-AllureReport.ps1 -RunId RUN_ID -Scope Combined -Kind All -Open
powershell -NoProfile -File scripts/Get-AllureReport.ps1 -ReportPath 'D:\skillbox\task-manager-autotests\test-runs\RUN_ID\reports\Combined-All\REPORT_ID\html'
```

Для Smoke/Regression укажите соответствующий Kind; отдельный тест через скрипт получает Selected. Прямой Maven и TestNG по умолчанию получают All. Скрипт проверяет Run ID каждого модуля и completed.marker. Завершение не означает успешность тестов: отчёты падений поддерживаются.

Автоматически выбирается предыдущий сохранённый отчёт того же scope, Kind и состава тестов. Переносится только каталог `history`, а не старые test results. buildOrder увеличивается. Можно явно выбрать `-PreviousReport '.../html'` или добавить архивный корень `-HistoryRoot`. Все предыдущие файлы сохраняются. Перестановка строк DataProvider меняет смысл порядкового номера варианта: при изменении порядка/состава данных начните отдельную цепочку истории. Allure 2 отображает ограниченное окно тренда; полные сохранённые отчёты при этом остаются на диске.

Перед генерацией и после неё выполняется аудит известных секретов. Локально он использует существующее DPAPI-хранилище; CI использует `-FromEnvironment`. Полные HTTP bodies, cookies, CSRF и реквизиты БД не сохраняются.

## Maven → Plugins → allure

В родительском build/plugins подключён `io.qameta.allure:allure-maven:2.15.2`, runtime Allure 2.46.1; executions, запускающих тесты, нет. После Reload All Maven Projects у корневого проекта доступны `allure:report`, `allure:serve`, а также help/install/aggregate. Плагин не наследуется модулями: это предотвращает случайную генерацию по устаревшим module/target.

Подготовьте вход тем же проверенным механизмом:

```powershell
powershell -NoProfile -File scripts/Get-AllureReport.ps1 -RunId RUN_ID -Scope Combined -Kind All -PrepareMaven
```

Скрипт напечатает точные команды `mvn -N allure:report ...` и `mvn -N allure:serve ...` с абсолютным путём к отдельной копии input и buildOrder. Для API/UI используйте соответствующий Scope. Эти команды можно вставить в Maven Run Configuration IntelliJ. `-N` оставляет выполнение на корневом проекте. Передавайте только подготовленную копию: plugin меняет executor.json и свойства во входном каталоге. По умолчанию вход `SELECT_PREPARED_INPUT` отсутствует, поэтому простой двойной щелчок не берёт случайные старые результаты.

`report` создаёт дополнительный preview в `test-runs/plugin-previews/<timestamp>`. Не переопределяйте output на существующий исторический каталог: CLI плагина может очищать output. `serve` генерирует временный preview и запускает локальный сервер; это не сохранённый исторический отчёт. Оба goal не запускают тесты. Основная цепочка History/Trend и постоянные HTML формируются PowerShell; plugin previews не используются автоматически как предшественники.

## TestNG в IntelliJ

1. Выполнить `mvn test-compile -DskipTests` из корня, Reload Maven Projects; reporting/src/main/java добавляется как test source обоим модулям.
2. В VM options API Tests и отдельных API-тестов:
   `-javaagent:"$PROJECT_DIR$/target/reporting-agent.jar"="$PROJECT_DIR$,api-tests"`
3. Для UI Tests и отдельных UI-тестов заменить api-tests на ui-tests.
4. Добавить AspectJ agent:
   `-javaagent:"$USER_HOME$/.m2/repository/org/aspectj/aspectjweaver/1.9.22.1/aspectjweaver-1.9.22.1.jar"`.
5. Сохранить все обязательные TEST-переменные, включая TEST_ALLOWED_DB_HOST и TEST_ALLOWED_DB_PORT. Для новых временных конфигураций установить VM options в шаблоне TestNG. Существующие локальные конфигурации workspace.xml дополнены агентами; файл игнорируется Git, поэтому на другой машине нужна эта настройка.
6. Maven All Tests оставляет `test -fae`: Surefire подключает оба agent автоматически.

Без bootstrap listener останавливает запуск с понятным сообщением до действий тестов. После clean агент нужно снова собрать. При открытой IDE проверить сохранение VM options: IDE может перезаписать workspace.xml из своей памяти.

## Диагностика и ограничения

Названия и описания всех 56 тестовых методов русские; бизнес-ожидания сохранены. Checks делегирует assertions TestNG и добавляет безопасные ожидаемые/фактические значения. Строковые значения скрыты, доступны длина и совпадение. HTTP-фильтр фиксирует разрешённый метод/путь, статус и длительность; проверки схем пишут отдельный результат. Подготовка и очистка остаются TestNG fixtures.

Стеки сохраняют классы исключений, frames и цепочки причин. Произвольные тексты исключений исключены, поскольку они могут содержать ответы сервера/персональные данные; первичный Throwable теста не заменяется. UI-диагностика содержит безопасный URL, readyState, браузер и сведения о наличии локатора. Полный произвольный locator не публикуется.

PNG представляет обезличенную структуру страницы: геометрические границы элементов на непрозрачном слое, без текста, полей и изображений. Одинаковые PNG исключаются в пределах invocation. При недоступном драйвере, неподтверждённом TEST-маршруте или невозможности маскирования прикладывается причина. Реальное маскирование DOM в Chrome требует отдельной ручной проверки; автономная проверка использует имитацию WebDriver и подтверждает механизм вложения/дедупликации, а не визуальное поведение Chrome.

CI сохраняет проверенные raw results и доступные отдельные/Combined HTML даже при падении тестов. History восстанавливается из cache отдельно от текущих результатов. Долговечность GitHub cache/artifacts ограничена политикой GitHub и retention; для постоянного архива скачивайте результаты. Workflow не запускался.

## Автономные проверки

```powershell
mvn test-compile -DskipTests
mvn dependency:build-classpath -Dmdep.outputFile=target/reporting-probe-classpath.txt
python scripts/checks/run-reporting-probes.py
pwsh -NoProfile -File scripts/checks/Test-ReportPipeline.ps1
mvn -N help:describe -Dplugin=io.qameta.allure:allure-maven:2.15.2 -Ddetail=true
```

Probes выбирают только синтетические классы отчётности; не создают Chrome, не обращаются к HTTP/БД. Pipeline использует fake CLI и проверяет перенос history и неизменность входа. Синтетические файлы оставляются в test-runs. Реальное открытие панели Maven IntelliJ, Chrome и интеграционные проверки остаются ручными; причина конкретного старого падения All Tests не доказана смешанными legacy-результатами.

## Исправление прямого запуска IntelliJ TestNG

Bootstrap перенесён в `reporting/agent`, вне общих test sources; Premain-Class — `ru.tarkovskiy.reporting.bootstrap.IsolatedRunAgent`. Путь JAR и два существующих VM options менять не нужно. После обновления выполнить `mvn test-compile -DskipTests` из корня.

Два аргумента `root,module` всегда обозначают отдельный запуск. Maven передаёт третий аргумент `maven=<session-key>` автоматически. При launcher IntelliJ `com.intellij.rt.testng.RemoteTestNGStarter` унаследованная Surefire Maven-метка игнорируется: validate для такого запуска не выполняется. Повторное подключение reporting-agent для того же root/module использует уже выбранный Run ID и не создаёт второй каталог. Конфликт root/module отклоняется. Настоящий Maven-запуск с отсутствующей сессией останавливается с объяснением, а не смешивает независимые результаты.

Проверка Windows Java 17 без TestNG/HTTP/JDBC: `python scripts/checks/check-agent-startup.py --java "C:/Program Files/Java/jdk17/bin/java.exe"`. Проверка использует JDK-only mains, в том числе заглушку с именем launcher IntelliJ; это не запуск тестов проекта.
