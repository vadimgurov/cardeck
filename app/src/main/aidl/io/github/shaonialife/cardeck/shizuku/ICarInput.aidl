// Shizuku UserService интерфейс：работать в постоянном режиме shell В процессе，Внедрение событий касания с миллисекундной задержкой。
// по сравнению с каждым разом spawn `input` команда（ок. 200-400ms），injectInputEvent Сервис системы прямого подключения。
package io.github.shaonialife.cardeck.shizuku;

import android.view.Surface;

interface ICarInput {
    boolean tap(int displayId, int x, int y);
    boolean swipe(int displayId, int startX, int startY, int endX, int endY, int durationMs);

    // Примитив непрерывного перетаскивания：Весь слайд доступен один раз DOWN，MOVE Ставки на событие за событием（реальная временная метка），
    // Примените отслеживание боковой скорости для расчета правильной инерции качения.。Поддерживает только один палец одновременно。
    boolean pointerDown(int displayId, int x, int y);
    boolean pointerMove(int displayId, int x, int y);
    boolean pointerUp(int displayId, int x, int y);

    // ключевая инъекция（iDrive Ручка → Клавиши со стрелками/Подтвердить ключ）：То же, что прикосновение injectInputEvent путь，
    // KeyEvent принести targetDisplayId Прямой фокус на виртуальном экране，миллисекундный уровень、Не прикасайтесь к главному экрану телефона。
    // Возврат в случае неудачи false（возврат вызывающего абонента `input -d <id> keyevent <code>`）。
    boolean keyEvent(int displayId, int keyCode);

    // Доверенный виртуальный экран（shell Разрешения）：На них могут свалиться новые задачи для сторонних приложений，
    // решить「Задачи, которые выполняются внутри приложения, перенаправляются на главный экран телефона.」вопрос（AOSP политика безопасности）。
    // trusted=false：Не объявляйте FLAG_TRUSTED——Сторонние задачи не могут быть размещены на этом экране системой.
    // （dock Экран содержит только свой собственный DockActivity，Удаление доверия может предотвратить ускользание других задач приложения.）。
    int createDisplay(String name, int width, int height, int dpi, in Surface surface, boolean trusted);
    boolean releaseDisplay(int displayId);

    // Динамическая настройка размера виртуального экрана（Только создатель может настроить）：Изменения конфигурации экранных приложений отправляются системой.，
    // Примените себя relayout——экран карты「разделенный экран ⇄ полноэкранный режим」Переключитесь, чтобы использовать эту способность（Задача не движется，Статус не потерян）。
    boolean resizeDisplay(int displayId, int width, int height, int dpi);

    // Физический источник питания главного экрана（SurfaceControl.setDisplayPowerMode）：false=отключение электроэнергии（экран истинного дыхания），
    // true=Восстановить свет。Система остается логически активной（Нет экрана блокировки、Не входя Doze），Виртуальный экран отображается как обычно。
    // Принцип тот же, что и scrcpy -S/--turn-screen-off；Возврат в случае неудачи false（ROM Несанкционированный/Не могу получить это display token）。
    boolean setPhysicalScreenPower(boolean on);

    boolean ping();
    String diag();

    // правда·ложный экран：SurfaceControl слой прямо сломан/Встроенный блок питания экрана.（POWER_MODE_OFF/NORMAL）。
    // Система остается интерактивной（нет сна、Нет keyguard、Виртуальный экран без последствий），Физическая панель действительно отключена。
    // необходимость ACCESS_SURFACE_FLINGER——shell Уже проведено（Испытанная слава Magic7 Pro granted=true）。
    // цепочка отражений SurfaceControl → DisplayControl（новая система token Миграция портала），Пошаговый откат。
    boolean setScreenPower(boolean on);

    // процесс обслуживания PID：Очистка стороны приложения「Не связанный в данный момент процесс」устаревания UserService привык исключать себя
    // （Виртуальный экран, принадлежащий старому процессу, не может пересекаться между процессами. release，Убить процесс — единственный способ его освободить.，увидеть CarInputClient）。
    int pid();
}
