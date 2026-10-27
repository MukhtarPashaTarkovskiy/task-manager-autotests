package ru.tarkovskiy.ui.tests;

import io.qameta.allure.Allure;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Severity;
import io.qameta.allure.SeverityLevel;
import io.qameta.allure.Story;
import io.qameta.allure.Step;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import ru.tarkovskiy.ui.base.BaseUiTest;
import ru.tarkovskiy.ui.db.TaskDbHelper;
import ru.tarkovskiy.ui.models.TaskDbRecord;
import ru.tarkovskiy.ui.pages.LoginPage;
import ru.tarkovskiy.ui.pages.TaskEditPage;
import ru.tarkovskiy.ui.pages.TaskFormPage;
import ru.tarkovskiy.ui.pages.TasksPage;
import ru.tarkovskiy.ui.utils.TaskDataGenerator;
import ru.tarkovskiy.ui.utils.TestData;
import java.sql.SQLException;
import java.time.LocalDate;
import org.testng.annotations.BeforeMethod;
import static org.testng.Assert.*;

public class TaskUiTest extends BaseUiTest {

    private String taskTitleForCleanup;
    private String boundaryTaskTitleForCleanup;
    private String boundaryTaskDescriptionForCleanup;

    @BeforeMethod(alwaysRun = true)
    public void resetCleanupData() {
        taskTitleForCleanup = null;
        boundaryTaskTitleForCleanup = null;
        boundaryTaskDescriptionForCleanup = null;
    }

    @AfterMethod(alwaysRun = true)
    public void cleanupTestTask() throws SQLException {

        if (boundaryTaskTitleForCleanup != null
                && boundaryTaskDescriptionForCleanup != null) {

            try {
                Long taskId = TaskDbHelper.findBoundaryTaskId(
                        boundaryTaskTitleForCleanup,
                        boundaryTaskDescriptionForCleanup,
                        TestData.EXISTING_USERNAME
                );

                if (taskId != null) {
                    int deleted = TaskDbHelper.deleteBoundaryTask(
                            taskId,
                            boundaryTaskTitleForCleanup,
                            boundaryTaskDescriptionForCleanup,
                            TestData.EXISTING_USERNAME
                    );

                    assertEquals(
                            deleted,
                            1,
                            "Граничная тестовая задача не была удалена"
                    );
                }
            } finally {
                boundaryTaskTitleForCleanup = null;
                boundaryTaskDescriptionForCleanup = null;
            }
        }

        if (taskTitleForCleanup == null) {
            return;
        }

        try {
            Long taskId = TaskDbHelper.findTaskIdByTitle(
                    taskTitleForCleanup,
                    TestData.EXISTING_USERNAME
            );

            if (taskId != null) {
                int deleted = TaskDbHelper.deleteTestTask(
                        taskId,
                        TestData.EXISTING_USERNAME,
                        taskTitleForCleanup
                );

                assertEquals(deleted, 1, "Тестовая задача не была удалена");

                assertFalse(
                        TaskDbHelper.taskExistsByTitle(
                                taskTitleForCleanup,
                                TestData.EXISTING_USERNAME
                        ),
                        "После очистки тестовая задача осталась в БД"
                );
            }
        } finally {
            taskTitleForCleanup = null;
        }
    }

    @Test(description = "UI: Отображение страницы задач после входа")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Отображение страницы задач после входа")
    @Severity(SeverityLevel.NORMAL)
    @Description("Отображение страницы задач после входа. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Отображение страницы задач после входа")
    public void shouldDisplayTasksPageAfterLogin() {

        // Авторизуемся существующим тестовым пользователем
        new LoginPage(driver)
                .open(BASE_URL)
                .login(
                        TestData.EXISTING_USERNAME,
                        TestData.getTestPassword()
                )
                .waitForSuccessfulLogin(BASE_URL);

        // Проверяем страницу задач
        TasksPage tasksPage = new TasksPage(driver);

        assertEquals(
                tasksPage.getCurrentUrl(),
                BASE_URL + "/tasks",
                "После авторизации пользователь должен попасть на страницу задач"
        );

        assertTrue(
                tasksPage.isPageTitleVisible(),
                "Заголовок страницы задач не отображается"
        );

        assertTrue(
                tasksPage.isCreateTaskLinkVisible(),
                "Кнопка создания задачи не отображается"
        );

        assertTrue(
                tasksPage.isTasksTableVisible(),
                "Таблица задач не отображается"
        );
    }

    @Test(description = "UI: Загрузка таблицы задач")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Загрузка таблицы задач")
    @Severity(SeverityLevel.NORMAL)
    @Description("Загрузка таблицы задач. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Загрузка таблицы задач")
    public void shouldLoadTasksTable() {

        new LoginPage(driver)
                .open(BASE_URL)
                .login(
                        TestData.EXISTING_USERNAME,
                        TestData.getTestPassword()
                )
                .waitForSuccessfulLogin(BASE_URL);

        TasksPage tasksPage = new TasksPage(driver);

        tasksPage.waitForTasksLoaded();

        assertTrue(
                tasksPage.isTasksLoadSuccessful(),
                "Не удалось загрузить список задач через API"
        );
    }

    @Test(description = "UI: Создание задачи с обязательными полями")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Создание задачи с обязательными полями")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Создание задачи с обязательными полями. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Создание задачи с обязательными полями")
    public void shouldCreateTaskSuccessfully() throws SQLException {

        taskTitleForCleanup = TaskDataGenerator.newTaskTitle();

        new LoginPage(driver)
                .open(BASE_URL)
                .login(
                        TestData.EXISTING_USERNAME,
                        TestData.getTestPassword()
                )
                .waitForSuccessfulLogin(BASE_URL);

        new TaskFormPage(driver)
                .open(BASE_URL)
                .enterTitle(taskTitleForCleanup)
                .enterDescription("Проверка создания задачи через UI")
                .submit()
                .waitForSuccessfulCreation(BASE_URL);
        ;

        new TasksPage(driver)
                .waitForTasksLoaded();

        TaskDbRecord task = TaskDbHelper.getTaskByTitle(
                taskTitleForCleanup,
                TestData.EXISTING_USERNAME
        );

        assertNotNull(task, "Созданная задача отсутствует в БД");

        assertEquals(
                task.title(),
                taskTitleForCleanup,
                "Название задачи не совпадает"
        );

        assertEquals(
                task.description(),
                "Проверка создания задачи через UI",
                "Описание задачи не совпадает"
        );

        assertEquals(task.status(), "TODO", "Неверный статус задачи");

        assertEquals(task.priority(), "LOW", "Неверный приоритет задачи");

        assertNull(task.deadline(), "Дата должна быть пустой");
    }

    @Test(description = "UI: Создание задачи со всеми полями")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Создание задачи со всеми полями")
    @Severity(SeverityLevel.NORMAL)
    @Description("Создание задачи со всеми полями. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Создание задачи со всеми полями")
    public void shouldCreateTaskWithAllFields() throws SQLException {

        taskTitleForCleanup = TaskDataGenerator.newTaskTitle();

        new LoginPage(driver)
                .open(BASE_URL)
                .login(
                        TestData.EXISTING_USERNAME,
                        TestData.getTestPassword()
                )
                .waitForSuccessfulLogin(BASE_URL);

        new TaskFormPage(driver)
                .open(BASE_URL)
                .enterTitle(taskTitleForCleanup)
                .enterDescription("Задача со всеми заполненными полями")
                .selectStatus("IN_PROGRESS")
                .selectPriority("HIGH")
                .enterDeadline("2026-12-31")
                .submit()
                .waitForSuccessfulCreation(BASE_URL);

        TaskDbRecord task = TaskDbHelper.getTaskByTitle(
                taskTitleForCleanup,
                TestData.EXISTING_USERNAME
        );

        assertNotNull(task, "Созданная задача отсутствует в БД");

        assertEquals(task.title(), taskTitleForCleanup);
        assertEquals(task.description(), "Задача со всеми заполненными полями");
        assertEquals(task.status(), "IN_PROGRESS");
        assertEquals(task.priority(), "HIGH");
        assertEquals(task.deadline(), LocalDate.of(2026, 12, 31));
    }

    @DataProvider(name = "taskData")
    public Object[][] taskData() {
        return new Object[][]{
                {"TODO", "LOW", "2026-12-01"},
                {"IN_PROGRESS", "MEDIUM", "2026-12-15"},
                {"DONE", "HIGH", "2026-12-31"}
        };
    }

    @Test(dataProvider = "taskData", description = "UI: Создание задачи с вариантами статуса приоритета и срока")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Создание задачи с вариантами статуса приоритета и срока")
    @Severity(SeverityLevel.NORMAL)
    @Description("Создание задачи с вариантами статуса приоритета и срока. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Создание задачи с вариантами статуса приоритета и срока")
    public void shouldCreateTaskWithDifferentData(
            String status,
            String priority,
            String deadline
    ) throws SQLException {

        taskTitleForCleanup = TaskDataGenerator.newTaskTitle();

        String description = "Проверка создания задачи через DataProvider";

        new LoginPage(driver)
                .open(BASE_URL)
                .login(
                        TestData.EXISTING_USERNAME,
                        TestData.getTestPassword()
                )
                .waitForSuccessfulLogin(BASE_URL);

        new TaskFormPage(driver)
                .open(BASE_URL)
                .enterTitle(taskTitleForCleanup)
                .enterDescription(description)
                .selectStatus(status)
                .selectPriority(priority)
                .enterDeadline(deadline)
                .submit()
                .waitForSuccessfulCreation(BASE_URL);

        TaskDbRecord task = TaskDbHelper.getTaskByTitle(
                taskTitleForCleanup,
                TestData.EXISTING_USERNAME
        );

        assertNotNull(task, "Созданная задача отсутствует в БД");

        assertEquals(task.title(), taskTitleForCleanup);
        assertEquals(task.description(), description);
        assertEquals(task.status(), status);
        assertEquals(task.priority(), priority);
        assertEquals(task.deadline(), LocalDate.parse(deadline));
    }

    @Test(description = "UI: Отклонение пустого названия задачи")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Отклонение пустого названия задачи")
    @Severity(SeverityLevel.NORMAL)
    @Description("Отклонение пустого названия задачи. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Отклонение пустого названия задачи")
    public void shouldNotCreateTaskWithEmptyTitle() {

        new LoginPage(driver)
                .open(BASE_URL)
                .login(
                        TestData.EXISTING_USERNAME,
                        TestData.getTestPassword()
                )
                .waitForSuccessfulLogin(BASE_URL);

        TaskFormPage taskFormPage = new TaskFormPage(driver)
                .open(BASE_URL)
                .enterDescription("Задача без названия")
                .submit();

        String errorText = taskFormPage.getTitleErrorText();
        assertTrue(
                errorText.contains("Название задачи не может быть пустым"),
                "Отсутствует сообщение об обязательности названия"
        );

        assertTrue(
                errorText.contains("Название задачи должно содержать от 3 до 100 символов"),
                "Отсутствует сообщение об ограничении длины названия"
        );

        assertEquals(
                driver.getCurrentUrl(),
                BASE_URL + "/tasks/new",
                "При пустом названии пользователь должен остаться на форме"
        );
    }

    @DataProvider(name = "invalidTaskTitles")
    public Object[][] invalidTaskTitles() {
        return new Object[][]{
                {"a"},
                {"ab"}
        };
    }

    @Test(dataProvider = "invalidTaskTitles", description = "UI: Отклонение слишком короткого названия задачи")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Отклонение слишком короткого названия задачи")
    @Severity(SeverityLevel.NORMAL)
    @Description("Отклонение слишком короткого названия задачи. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Отклонение слишком короткого названия задачи")
    public void shouldRejectTooShortTaskTitle(String title) {

        new LoginPage(driver)
                .open(BASE_URL)
                .login(
                        TestData.EXISTING_USERNAME,
                        TestData.getTestPassword()
                )
                .waitForSuccessfulLogin(BASE_URL);

        TaskFormPage taskFormPage = new TaskFormPage(driver)
                .open(BASE_URL)
                .enterTitle(title)
                .enterDescription("Проверка минимальной длины названия")
                .submit();

        String errorText = taskFormPage.getTitleErrorText();

        assertTrue(
                errorText.contains(
                        "Название задачи должно содержать от 3 до 100 символов"
                ),
                "Неверное сообщение об ограничении длины названия"
        );

        assertEquals(
                driver.getCurrentUrl(),
                BASE_URL + "/tasks/new",
                "При невалидном названии пользователь должен остаться на форме"
        );
    }

    @DataProvider(name = "validTaskTitleLengths")
    public Object[][] validTaskTitleLengths() {
        return new Object[][]{
                {3},
                {4},
                {99},
                {100}
        };
    }

    @Test(dataProvider = "validTaskTitleLengths", description = "UI: Допустимая длина названия задачи")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Допустимая длина названия задачи")
    @Severity(SeverityLevel.NORMAL)
    @Description("Допустимая длина названия задачи. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Допустимая длина названия задачи")
    public void shouldCreateTaskWithValidTitleLength(int length)
            throws SQLException {

        boundaryTaskTitleForCleanup = "a".repeat(length);
        boundaryTaskDescriptionForCleanup =
                TaskDataGenerator.newBoundaryTaskDescription();

        new LoginPage(driver)
                .open(BASE_URL)
                .login(
                        TestData.EXISTING_USERNAME,
                        TestData.getTestPassword()
                )
                .waitForSuccessfulLogin(BASE_URL);

        new TaskFormPage(driver)
                .open(BASE_URL)
                .enterTitle(boundaryTaskTitleForCleanup)
                .enterDescription(boundaryTaskDescriptionForCleanup)
                .submit()
                .waitForSuccessfulCreation(BASE_URL);

        Long taskId = TaskDbHelper.findBoundaryTaskId(
                boundaryTaskTitleForCleanup,
                boundaryTaskDescriptionForCleanup,
                TestData.EXISTING_USERNAME
        );

        assertNotNull(
                taskId,
                "Задача с названием длиной " + length
                        + " символов отсутствует в БД"
        );
    }

    @Test(description = "UI: Ограничение ввода названия задачи")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Ограничение ввода названия задачи")
    @Severity(SeverityLevel.NORMAL)
    @Description("Ограничение ввода названия задачи. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Ограничение ввода названия задачи")
    public void shouldLimitTaskTitleTo100Characters() {

        new LoginPage(driver)
                .open(BASE_URL)
                .login(
                        TestData.EXISTING_USERNAME,
                        TestData.getTestPassword()
                )
                .waitForSuccessfulLogin(BASE_URL);

        String title = "a".repeat(101);

        TaskFormPage taskFormPage = new TaskFormPage(driver)
                .open(BASE_URL)
                .enterTitle(title);

        String actualTitle = taskFormPage.getTitleValue();

        assertEquals(
                actualTitle.length(),
                100,
                "Поле названия должно ограничивать ввод 100 символами"
        );

        assertEquals(
                actualTitle,
                "a".repeat(100),
                "Название задачи обрезано некорректно"
        );
    }

    @DataProvider(name = "taskDescriptionLengths")
    public Object[][] taskDescriptionLengths() {
        return new Object[][]{
                {0},
                {1},
                {999},
                {1000},
                {1001}
        };
    }

    @Test(dataProvider = "taskDescriptionLengths", description = "UI: Граничная длина описания задачи")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Граничная длина описания задачи")
    @Severity(SeverityLevel.NORMAL)
    @Description("Граничная длина описания задачи. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Граничная длина описания задачи")
    public void shouldHandleDescriptionBoundaryValues(int length)
            throws SQLException {

        taskTitleForCleanup = TaskDataGenerator.newTaskTitle();

        String description = "a".repeat(length);
        int expectedLength = Math.min(length, 1000);

        new LoginPage(driver)
                .open(BASE_URL)
                .login(
                        TestData.EXISTING_USERNAME,
                        TestData.getTestPassword()
                )
                .waitForSuccessfulLogin(BASE_URL);

        TaskFormPage taskFormPage = new TaskFormPage(driver)
                .open(BASE_URL)
                .enterTitle(taskTitleForCleanup)
                .enterDescription(description);

        String actualDescription = taskFormPage.getDescriptionValue();

        assertEquals(
                actualDescription.length(),
                expectedLength,
                "Некорректное ограничение длины описания"
        );

        taskFormPage
                .submit()
                .waitForSuccessfulCreation(BASE_URL);

        TaskDbRecord task = TaskDbHelper.getTaskByTitle(
                taskTitleForCleanup,
                TestData.EXISTING_USERNAME
        );

        assertNotNull(task, "Созданная задача отсутствует в БД");

        assertEquals(
                task.description(),
                actualDescription,
                "Описание некорректно сохранилось в PostgreSQL"
        );
    }

    @Test(description = "UI: Редактирование задачи и проверка БД")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Редактирование задачи и проверка БД")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Редактирование задачи и проверка БД. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Редактирование задачи и проверка БД")
    public void shouldEditTaskSuccessfully() throws SQLException {

        taskTitleForCleanup = TaskDataGenerator.newTaskTitle();

        new LoginPage(driver)
                .open(BASE_URL)
                .login(TestData.EXISTING_USERNAME, TestData.getTestPassword())
                .waitForSuccessfulLogin(BASE_URL);

        new TaskFormPage(driver)
                .open(BASE_URL)
                .enterTitle(taskTitleForCleanup)
                .enterDescription("Исходное описание")
                .submit()
                .waitForSuccessfulCreation(BASE_URL);

        Long taskId = TaskDbHelper.findTaskIdByTitle(
                taskTitleForCleanup,
                TestData.EXISTING_USERNAME
        );

        assertNotNull(taskId, "Созданная задача не найдена в БД");

        new TaskEditPage(driver)
                .open(BASE_URL, taskId)
                .enterDescription("Обновлённое описание")
                .selectStatus("IN_PROGRESS")
                .selectPriority("HIGH")
                .save()
                .waitForSuccessfulUpdate(BASE_URL);

        TaskDbRecord updatedTask = TaskDbHelper.getTaskByTitle(
                taskTitleForCleanup,
                TestData.EXISTING_USERNAME
        );

        assertNotNull(updatedTask, "Задача не найдена после редактирования");

        assertEquals(updatedTask.description(), "Обновлённое описание");
        assertEquals(updatedTask.status(), "IN_PROGRESS");
        assertEquals(updatedTask.priority(), "HIGH");
    }

    @Test(description = "UI: Удаление задачи и проверка отсутствия в БД")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Удаление задачи и проверка отсутствия в БД")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Удаление задачи и проверка отсутствия в БД. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Удаление задачи и проверка отсутствия в БД")
    public void shouldDeleteTaskSuccessfully() throws SQLException {

        taskTitleForCleanup = TaskDataGenerator.newTaskTitle();

        // Авторизуемся
        new LoginPage(driver)
                .open(BASE_URL)
                .login(TestData.EXISTING_USERNAME, TestData.getTestPassword())
                .waitForSuccessfulLogin(BASE_URL);

        // Создаём тестовую задачу через UI
        new TaskFormPage(driver)
                .open(BASE_URL)
                .enterTitle(taskTitleForCleanup)
                .enterDescription("Задача для проверки удаления")
                .submit()
                .waitForSuccessfulCreation(BASE_URL);

        // Находим ID созданной задачи в PostgreSQL
        Long taskId = TaskDbHelper.findTaskIdByTitle(
                taskTitleForCleanup,
                TestData.EXISTING_USERNAME
        );

        assertNotNull(taskId, "Созданная задача не найдена в БД");

        // Удаляем задачу через UI
        new TasksPage(driver)
                .waitForTasksLoaded()
                .deleteTask(taskId);

        // Проверяем фактическое удаление из PostgreSQL
        assertFalse(
                TaskDbHelper.taskExistsByTitle(
                        taskTitleForCleanup,
                        TestData.EXISTING_USERNAME
                ),
                "После удаления через UI задача осталась в PostgreSQL"
        );
    }

    @Test(description = "UI: Отмена удаления задачи")
    @Epic("Task Manager")
    @Feature("Управление задачами")
    @Story("Отмена удаления задачи")
    @Severity(SeverityLevel.NORMAL)
    @Description("Отмена удаления задачи. Выполнить действия сценария и проверить существующие ожидания интерфейса, HTTP или PostgreSQL.")
    @Step("Отмена удаления задачи")
    public void shouldCancelTaskDeletion() throws SQLException {

        taskTitleForCleanup = TaskDataGenerator.newTaskTitle();

        // Авторизуемся
        new LoginPage(driver)
                .open(BASE_URL)
                .login(TestData.EXISTING_USERNAME, TestData.getTestPassword())
                .waitForSuccessfulLogin(BASE_URL);

        // Создаём тестовую задачу через UI
        new TaskFormPage(driver)
                .open(BASE_URL)
                .enterTitle(taskTitleForCleanup)
                .enterDescription("Проверка отмены удаления")
                .submit()
                .waitForSuccessfulCreation(BASE_URL);

        // Находим ID созданной задачи
        Long taskId = TaskDbHelper.findTaskIdByTitle(
                taskTitleForCleanup,
                TestData.EXISTING_USERNAME
        );

        assertNotNull(taskId, "Созданная задача не найдена в БД");

        // Открываем окно удаления и нажимаем «Отмена»
        new TasksPage(driver)
                .waitForTasksLoaded()
                .cancelTaskDeletion(taskId);

        // Проверяем, что задача осталась в PostgreSQL
        assertTrue(
                TaskDbHelper.taskExistsByTitle(
                        taskTitleForCleanup,
                        TestData.EXISTING_USERNAME
                ),
                "После отмены удаления задача отсутствует в БД"
        );
    }
}