# Безопасная диагностика UI-навигации

Диагностика не создаёт HTTP-клиентов, не обращается к БД, не меняет ChromeOptions,
выбор ChromeDriver, workflow, бизнес-тесты или ограничения скриншотов.

## Вложения

`Browser session` сохраняется при создании каждой сессии. Версии Chrome и
ChromeDriver берутся из фактических capabilities (`browserVersion` и
`chrome.chromedriverVersion`), а не из PATH или предупреждения Selenium Manager.
Записываются версии Java/Selenium, ОС/архитектура, число доступных JVM процессоров,
максимальный heap JVM, запрошенный режим headless/размер окна, pageLoadStrategy,
таймауты capabilities, тип proxy и только факт наличия proxy-переменных или явного
пути драйвера. Пути, адреса proxy, session ID, debugger address и произвольные
capabilities не выводятся. Те же сведения включены в диагностику ошибки.

`Browser navigation` сохраняется после каждого вызова `BasePage.navigate()`,
включая успешный. Содержит UTC-время начала, длительность driver.get и всей
проверки, разрешённый ожидаемый маршрут, безопасные категории URL после get и
после ожидания, exactExpectedUrl, readyState, число DOM-элементов, наличие видимых
признаков ожидаемой формы, HTTP-статус и числовые Navigation Timing поля.

Классификация различает DATA_DOCUMENT (точный data:, отдельно обозначен DATA_EMPTY),
ABOUT_DOCUMENT (about:blank отдельно), CHROME_ERROR, CHROME_INTERNAL, HTTP_ERROR,
NETWORK_ERROR, TEST_READY, TEST_READY_HTTP_UNKNOWN и UNEXPECTED_OR_NOT_READY.
NETWORK_ERROR означает только известный код из исключения WebDriver или встроенной
страницы ошибки Chrome; произвольный текст не сохраняется.
Код ERR_HTTP_RESPONSE_CODE_FAILURE классифицируется как HTTP_ERROR, даже если
числовой HTTP-статус недоступен. Состояние документа и
ошибка команды отражаются независимо: commandOrReadinessFailed не теряется, даже
если текущий документ выглядит готовым.

## Готовность и бюджет

Перед get разрешены только прежний TEST origin и известные маршруты без query и
fragment. После get проверяются точный URL, complete и видимые признаки login,
register, списка задач, создания или редактирования. Известный HTTP-ответ с кодом
ошибки не считается готовностью, даже если в DOM остались элементы формы.

После get действует прежний 15-секундный бюджет с опросом через 500 мс. Проверка
готовности и первое последующее ожидание элемента/условия на этом Page делят этот
бюджет. Последующие самостоятельные ожидания сохраняют прежние 15 секунд.
Исходный page-load timeout WebDriver не меняется. Повторного get и повторов тестов нет.

## Ограничения сетевых доказательств

Используется только пассивный PerformanceNavigationTiming текущего документа.
responseStatus=0, отсутствие свойства или недоступность JavaScript означают
UNKNOWN, а не HTTP 200. TEST_READY_HTTP_UNKNOWN подтверждает маршрут/структуру,
но не HTTP-статус. Известный числовой статус относится к текущему документу;
история всех промежуточных ответов не записывается.

redirectCount не содержит URL цепочки. Нулевое значение не доказывает отсутствие
редиректов, в том числе из-за ограничений наблюдаемости браузера. Сетевой сбой без
известного кода может остаться UNKNOWN. Подробная сетевая трассировка требует
отдельного этапа; никаких дополнительных запросов для подтверждения не выполняется.

HTML, тела ответов, заголовки, cookies, значения полей, query/fragment и произвольные
тексты ошибок не сохраняются. Конфигурационные секреты дополнительно редактируются
перед выводом в консоль и Allure. SafeScreenshot не изменён.

## Автономные проверки

`scripts/checks/check-ui-navigation.py` компилирует UI/shared reporting исходники
и запускает только standalone `UiNavigationProbe` на динамическом fake WebDriver.
Никакие методы TestNG, RenderReadiness, ChromeDriver, HTTP или JDBC не запускаются.
Нужны JDK и уже сохранённый dependency classpath; загрузок зависимостей нет.
Компиляция и синтетические вложения создаются в отдельном временном каталоге.

```text
python3 scripts/checks/check-ui-navigation.py --jdk-home "/mnt/c/Program Files/Java/jdk17"
node scripts/checks/check-navigation-snapshot.cjs
```

Первый аргумент нужен, если установленный JRE содержит compiler module без ct.sym:
проверка API и bytecode тогда выполняется против указанного Java 17. В обычном JDK
можно опустить аргумент: применяется --release 17. JS-проверка выполняет исходный
snapshot-скрипт в VM на фиктивных DOM/performance без браузера.

Проверенные сохранённые локальные результаты 8 ноября:
`20261108T071432625-43211d3f62dd4fa1ad34c0608cf50ca3` — UI Smoke 4 passed;
`20261108T072134400-75b5ea9b4a3849b4bed2f7bbebca6158` — UI Regression 66 passed.
В исходных Allure JSON/TXT/properties фактическая версия ChromeDriver отсутствует.
Generation logs относятся к Allure CLI. Наличие драйвера в PATH/кэше сегодня не
доказывает версию, обслуживавшую эти успешные сессии.
