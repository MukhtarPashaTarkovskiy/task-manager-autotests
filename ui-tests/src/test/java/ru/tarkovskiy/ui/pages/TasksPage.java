package ru.tarkovskiy.ui.pages;

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

    public TasksPage open(String baseUrl) {
        driver.get(baseUrl + TASKS_URL);

        wait.until(
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

    public TasksPage waitForTasksLoaded() {

        wait.until(driver -> {
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

    public TasksPage deleteTask(long taskId) {

        By taskRow = By.xpath(
                "//tbody[@id='tasks-table-body']/tr[td[1][normalize-space()='"
                        + taskId + "']]"
        );

        WebElement row = wait.until(
                ExpectedConditions.visibilityOfElementLocated(taskRow)
        );

        row.findElement(
                By.xpath(".//button[normalize-space()='Удалить']")
        ).click();

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("delete-task-modal")
        ));

        click(By.id("confirm-delete-task"));

        wait.until(ExpectedConditions.invisibilityOfElementLocated(taskRow));

        return this;
    }

    public TasksPage cancelTaskDeletion(long taskId) {

        By taskRow = By.xpath(
                "//tbody[@id='tasks-table-body']/tr[td[1][normalize-space()='"
                        + taskId + "']]"
        );

        WebElement row = wait.until(
                ExpectedConditions.visibilityOfElementLocated(taskRow)
        );

        row.findElement(
                By.xpath(".//button[normalize-space()='Удалить']")
        ).click();

        wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.id("delete-task-modal")
        ));

        click(By.id("cancel-delete-task"));

        wait.until(ExpectedConditions.invisibilityOfElementLocated(
                By.id("delete-task-modal")
        ));

        return this;
    }
}