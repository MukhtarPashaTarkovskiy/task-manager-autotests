package ru.tarkovskiy.ui.pages;

import io.qameta.allure.Step;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import ru.tarkovskiy.ui.base.BasePage;

public class TasksPage extends BasePage {

    private static final String TASKS_URL = "/tasks";
    private final By pageTitle = By.tagName("h1");
    private final By createTaskLink = By.cssSelector("a[href='/tasks/new']");
    private final By tasksTable = By.cssSelector(".tasks-table");
    private final By tasksTableBody = By.id("tasks-table-body");

    public TasksPage(WebDriver driver) {
        super(driver);
    }

    @Step("Открыть страницу")
    public TasksPage open(String baseUrl) {
        navigate(baseUrl + TASKS_URL);

        await(
                ExpectedConditions.urlToBe(baseUrl + TASKS_URL)
        );

        return this;
    }

    public String getCurrentUrl() {
        return driver.getCurrentUrl();
    }

    public boolean isPageTitleVisible() {
        return findVisible(pageTitle).isDisplayed();
    }

    public boolean isCreateTaskLinkVisible() {
        return findVisible(createTaskLink).isDisplayed();
    }

    public boolean isTasksTableVisible() {
        return findVisible(tasksTable).isDisplayed();
    }

    @Step("Дождаться загрузки списка задач")
    public TasksPage waitForTasksLoaded() {

        await(driver -> {
            var rows = driver.findElements(
                    By.cssSelector("#tasks-table-body tr")
            );

            return !rows.isEmpty();
        });

        return this;
    }

    public boolean isTasksLoadSuccessful() {

        waitForTasksLoaded();

        String tableText = findVisible(tasksTableBody).getText();

        return !tableText.contains(
                "Не удалось загрузить задачи"
        );
    }

    @Step("Подтвердить удаление задачи")
    public TasksPage deleteTask(long taskId) {

        By taskRow = By.xpath(
                "//tbody[@id='tasks-table-body']/tr[td[1][normalize-space()='"
                        + taskId + "']]"
        );

        WebElement row = await(
                ExpectedConditions.visibilityOfElementLocated(taskRow)
        );

        row.findElement(
                By.xpath(".//button[normalize-space()='Удалить']")
        ).click();

        await(ExpectedConditions.visibilityOfElementLocated(
                By.id("delete-task-modal")
        ));

        click(By.id("confirm-delete-task"));

        await(ExpectedConditions.invisibilityOfElementLocated(taskRow));

        return this;
    }

    @Step("Отменить удаление задачи")
    public TasksPage cancelTaskDeletion(long taskId) {

        By taskRow = By.xpath(
                "//tbody[@id='tasks-table-body']/tr[td[1][normalize-space()='"
                        + taskId + "']]"
        );

        WebElement row = await(
                ExpectedConditions.visibilityOfElementLocated(taskRow)
        );

        row.findElement(
                By.xpath(".//button[normalize-space()='Удалить']")
        ).click();

        await(ExpectedConditions.visibilityOfElementLocated(
                By.id("delete-task-modal")
        ));

        click(By.id("cancel-delete-task"));

        await(ExpectedConditions.invisibilityOfElementLocated(
                By.id("delete-task-modal")
        ));

        return this;
    }
}