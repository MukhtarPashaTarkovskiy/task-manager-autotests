package ru.tarkovskiy.ui.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import ru.tarkovskiy.ui.base.BasePage;
import org.openqa.selenium.support.ui.Select;

public class TaskFormPage extends BasePage {

    private final By titleInput = By.id("title");
    private final By descriptionInput = By.id("description");
    private final By createButton = By.id("create-task-button");
    private final By deadlineInput = By.id("deadline");
    private final By statusSelect = By.id("status");
    private final By prioritySelect = By.id("priority");
    private final By titleError = By.id("title-error");


    public TaskFormPage(WebDriver driver) {
        super(driver);
    }

    public TaskFormPage open(String baseUrl) {
        driver.get(baseUrl + "/tasks/new");
        return this;
    }

    public TaskFormPage enterTitle(String title) {
        type(titleInput, title);
        return this;
    }

    public TaskFormPage enterDescription(String description) {
        type(descriptionInput, description);
        return this;
    }

    public TaskFormPage submit() {
        click(createButton);
        return this;
    }

    public TaskFormPage waitForSuccessfulCreation(String baseUrl) {
        wait.until(
                ExpectedConditions.urlToBe(baseUrl + "/tasks")
        );
        return this;
    }

    public TaskFormPage enterDeadline(String deadline) {

        WebElement element = findVisible(deadlineInput);

        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value = arguments[1];" +
                        "arguments[0].dispatchEvent(new Event('input', { bubbles: true }));" +
                        "arguments[0].dispatchEvent(new Event('change', { bubbles: true }));",
                element,
                deadline
        );

        // Проверяем, что дата действительно установилась
        String actualDate = element.getDomProperty("value");

        if (!deadline.equals(actualDate)) {
            throw new IllegalStateException(
                    "Дата не установлена. Ожидали: "
                            + deadline + ", получили: " + actualDate
            );
        }

        return this;
    }

    public TaskFormPage selectStatus(String status) {
        new Select(findVisible(statusSelect))
                .selectByValue(status);
        return this;
    }

    public TaskFormPage selectPriority(String priority) {
        new Select(findVisible(prioritySelect))
                .selectByValue(priority);
        return this;
    }

    public boolean isTitleRequired() {
        WebElement element = findVisible(titleInput);
        return (Boolean) ((JavascriptExecutor) driver)
                .executeScript(
                        "return arguments[0].validity.valueMissing;",
                        element
                );
    }

    public String getTitleValidationMessage() {
        return findVisible(titleInput).getDomProperty("validationMessage");
    }

    public String getTitleErrorText() {
        return wait.until(
                ExpectedConditions.visibilityOfElementLocated(titleError)
        ).getText();
    }

    public String getTitleValue() {
        return findVisible(titleInput).getDomProperty("value");
    }

    public String getDescriptionValue() {
        return findVisible(descriptionInput).getDomProperty("value");
    }
}