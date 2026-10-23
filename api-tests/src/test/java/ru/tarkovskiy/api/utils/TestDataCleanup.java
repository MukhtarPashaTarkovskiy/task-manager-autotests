package ru.tarkovskiy.api.utils;

import ru.tarkovskiy.api.db.TaskDbHelper;
import ru.tarkovskiy.api.db.UserDbHelper;

import java.util.ArrayList;
import java.util.List;

public class TestDataCleanup {

    private record TrackedTask(long id, String username, String title) {}
    private record TrackedTaskByTitle(String username, String title) {}
    private record TrackedBoundaryTask(long id, String username, String title) {}

    private final List<TrackedTask> tasks = new ArrayList<>();
    private final List<TrackedTaskByTitle> tasksByTitle = new ArrayList<>();
    private final List<TrackedBoundaryTask> boundaryTasks = new ArrayList<>();
    private final List<String> emails = new ArrayList<>();
    private final List<String> usernames = new ArrayList<>();

    public void trackTask(long id, String username, String title) {
        validateOrdinaryTask(id, username, title);
        tasks.add(new TrackedTask(id, username, title));
    }

    public void track(String email) {
        if (email == null || !email.matches("^qa_[0-9a-f]{32}@example\\.test$")) {
            throw new IllegalArgumentException("Недопустимый email тестового пользователя");
        }
        emails.add(email);
    }

    public void trackUsername(String username) {
        if (username == null || !username.matches("^qa_user_[0-9a-f]{20}$")) {
            throw new IllegalArgumentException("Недопустимый логин тестового пользователя");
        }
        usernames.add(username);
    }

    public void trackTaskByTitle(String username, String title) {
        if (!TestData.EXISTING_USERNAME.equals(username)
                || !TaskDbHelper.isTestTaskTitle(title)) {
            throw new IllegalArgumentException("Недопустимые данные тестовой задачи");
        }
        tasksByTitle.add(new TrackedTaskByTitle(username, title));
    }

    public void trackBoundaryTask(long id, String username, String title) {
        if (id <= 0 || !TestData.EXISTING_USERNAME.equals(username)
                || !TaskDbHelper.isBoundaryTitle(title)) {
            throw new IllegalArgumentException(
                    "Недопустимые данные граничной тестовой задачи"
            );
        }
        boundaryTasks.add(new TrackedBoundaryTask(id, username, title));
    }

    public void cleanup() throws Exception {
        Exception cleanupFailure = null;
        try {
            for (TrackedTask task : tasks) {
                try {
                    deleteAndVerifyTask(task.id(), task.username(), task.title());
                } catch (Exception e) {
                    cleanupFailure = collectError(cleanupFailure, e);
                }
            }

            for (TrackedTaskByTitle task : tasksByTitle) {
                try {
                    Long id = TaskDbHelper.findTaskIdByTitle(task.title(), task.username());
                    if (id != null) {
                        deleteAndVerifyTask(id, task.username(), task.title());
                    }
                } catch (Exception e) {
                    cleanupFailure = collectError(cleanupFailure, e);
                }
            }

            for (TrackedBoundaryTask task : boundaryTasks) {
                try {
                    int deleted = TaskDbHelper.deleteBoundaryTestTask(
                            task.id(), task.username(), task.title()
                    );
                    verifyDeleted(deleted, task.id(), task.username());
                } catch (Exception e) {
                    cleanupFailure = collectError(cleanupFailure, e);
                }
            }

            for (String email : emails) {
                try {
                    UserDbHelper.deleteUser(email);
                    if (UserDbHelper.userExists(email)) {
                        throw new IllegalStateException("Пользователь остался в БД: " + email);
                    }
                } catch (Exception e) {
                    cleanupFailure = collectError(cleanupFailure, e);
                }
            }

            for (String username : usernames) {
                try {
                    UserDbHelper.deleteUserByUsername(username);
                    if (UserDbHelper.userExistsByUsername(username)) {
                        throw new IllegalStateException(
                                "Пользователь остался в БД: " + username
                        );
                    }
                } catch (Exception e) {
                    cleanupFailure = collectError(cleanupFailure, e);
                }
            }
        } finally {
            emails.clear();
            usernames.clear();
            tasks.clear();
            tasksByTitle.clear();
            boundaryTasks.clear();
        }

        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private void deleteAndVerifyTask(long id, String username, String title)
            throws Exception {
        int deleted = TaskDbHelper.deleteTestTask(id, username, title);
        verifyDeleted(deleted, id, username);
    }

    private void verifyDeleted(int deleted, long id, String username) throws Exception {
        if (deleted != 1) {
            throw new IllegalStateException("Не удалось удалить тестовую задачу: " + id);
        }
        if (TaskDbHelper.taskExists(id, username)) {
            throw new IllegalStateException("Задача осталась в БД: " + id);
        }
    }

    private Exception collectError(Exception previous, Exception error) {
        if (previous == null) {
            previous = new Exception("Ошибка очистки тестовых данных");
        }
        previous.addSuppressed(error);
        return previous;
    }

    private void validateOrdinaryTask(long id, String username, String title) {
        if (id <= 0 || !TestData.EXISTING_USERNAME.equals(username)
                || !TaskDbHelper.isTestTaskTitle(title)) {
            throw new IllegalArgumentException("Недопустимые данные тестовой задачи");
        }
    }
}
