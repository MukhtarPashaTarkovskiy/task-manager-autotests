package ru.tarkovskiy.ui.pages;

import io.qameta.allure.Step;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import ru.tarkovskiy.ui.base.BasePage;

public class TaskEditPage extends BasePage {

    private final By titleInput = By.id("title");
    private final By descriptionInput = By.id("description");
    private final By statusSelect = By.id("status");
    private final By prioritySelect = By.id("priority");
    private final By saveButton = By.id("save-task-button");

    public TaskEditPage(WebDriver driver) {
        super(driver);
    }

    @Step("Открыть страницу")
    public TaskEditPage open(String baseUrl, long taskId) {
        navigate(baseUrl + "/tasks/" + taskId + "/edit");

        // Ждём окончания асинхронной загрузки задачи.
        // В нашем тесте название созданной задачи не пустое.
        await(d -> {
            String title = d.findElement(titleInput)
                    .getDomProperty("value");

            return title != null && !title.isBlank();
        });

        return this;
    }

    @Step("Заполнить название задачи")
    public TaskEditPage enterTitle(String title) {
        type(titleInput, title);
        return this;
    }

    @Step("Заполнить описание задачи")
    public TaskEditPage enterDescription(String description) {
        type(descriptionInput, description);
        return this;
    }

    @Step("Выбрать статус задачи")
    public TaskEditPage selectStatus(String status) {
        new Select(findVisible(statusSelect))
                .selectByValue(status);
        return this;
    }

    @Step("Выбрать приоритет задачи")
    public TaskEditPage selectPriority(String priority) {
        new Select(findVisible(prioritySelect))
                .selectByValue(priority);
        return this;
    }

    @Step("Сохранить изменения задачи")
    public TaskEditPage save() {
        click(saveButton);
        return this;
    }

    @Step("Дождаться сохранения задачи")
    public TaskEditPage waitForSuccessfulUpdate(String baseUrl) {
        await(ExpectedConditions.urlToBe(
                baseUrl + "/tasks"
        ));
        return this;
    }
}
