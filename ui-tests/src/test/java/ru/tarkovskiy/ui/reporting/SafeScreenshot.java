package ru.tarkovskiy.ui.reporting;

import io.qameta.allure.Allure;
import org.openqa.selenium.*;
import org.testng.Reporter;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Fail closed: only an opaque, top-layer structural mask is captured, never page text. */
public final class SafeScreenshot {
    private SafeScreenshot() { }
    public static void attach(WebDriver driver, String name) {
        WebElement mask = null;
        try {
            if (driver == null) { unavailable("WebDriver не создан; ошибка подготовки браузера"); return; }
            if (!(driver instanceof JavascriptExecutor js)) { unavailable("WebDriver не поддерживает безопасное маскирование"); return; }
            URI url = URI.create(driver.getCurrentUrl());
            if (!"https".equals(url.getScheme()) || !"task-manager-test-588r.onrender.com".equals(url.getHost()) || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)
                || !url.getPath().matches("/(?:login|register|tasks(?:/new|/[0-9]+/edit)?)?")) {
                unavailable("Страница не входит в разрешённые маршруты TEST; безопасность изображения не подтверждена"); return;
            }
            // No input values, attributes, text, images, HTML or canvas pixels are read.
            mask = (WebElement) js.executeScript("""
                if (!document.body || typeof HTMLDialogElement === 'undefined') return null;
                const boxes = [...document.querySelectorAll('input,textarea,select,button,table,thead,tbody,tr,h1,h2,form')]
                    .map(e => { const r=e.getBoundingClientRect(); return [r.x,r.y,r.width,r.height]; });
                const d=document.createElement('dialog');
                d.style.cssText='all:initial!important;position:fixed!important;inset:0!important;margin:0!important;padding:0!important;border:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;background:white!important;opacity:1!important;overflow:hidden!important;transform:none!important;filter:none!important;';
                const c=document.createElement('canvas'); c.width=innerWidth; c.height=innerHeight;
                c.style.cssText='all:initial!important;display:block!important;width:100%!important;height:100%!important;opacity:1!important;filter:none!important;';
                const ctx=c.getContext('2d'); if(!ctx) return null;
                ctx.fillStyle='#ffffff';ctx.fillRect(0,0,c.width,c.height);
                ctx.strokeStyle='#607080';ctx.fillStyle='#e8edf2';
                for(const [x,y,w,h] of boxes) {ctx.fillRect(x,y,w,h);ctx.strokeRect(x,y,w,h);}
                d.append(c); document.body.append(d); d.showModal();
                return c;
                """);
            if (mask == null) { unavailable("Браузер не поддерживает непрозрачную маску страницы"); return; }
            byte[] png = mask.getScreenshotAs(OutputType.BYTES);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));
            var result = Reporter.getCurrentTestResult();
            String key = SafeScreenshot.class.getName() + hash;
            if (result != null && Boolean.TRUE.equals(result.getAttribute(key))) return;
            Allure.addAttachment(name + " — обезличенная структура страницы", "image/png", new ByteArrayInputStream(png), ".png");
            if (result != null) result.setAttribute(key, true);
            Allure.addAttachment("Маскирование снимка", "text/plain", "Все тексты, значения полей и изображения скрыты. Сохранены только геометрические границы элементов интерфейса.", ".txt");
        } catch (Exception failure) {
            unavailable("Снимок или безопасное маскирование недоступны: " + failure.getClass().getSimpleName());
        } finally {
            if (mask != null && driver instanceof JavascriptExecutor js) {
                try { js.executeScript("arguments[0].parentElement.close();arguments[0].parentElement.remove();", mask); }
                catch (RuntimeException ignored) { /* Never replace the original failure. */ }
            }
        }
    }
    private static void unavailable(String reason) {
        Allure.addAttachment("Скриншот не приложен", "text/plain", reason, ".txt");
    }
}
