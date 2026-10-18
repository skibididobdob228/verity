package dev.verity.client;

import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The one question asked on entering a world: may he reach for your real computer, and, if you care to tell him, what is
 * your name. "No" and Esc both mean no; the name box may be left empty.
 */
final class ConsentScreen extends Screen {
    private static final Component ASK_NAME = Component.literal("Как тебя зовут на самом деле? (можно не писать)");
    private static final Component HINT = Component.literal("Если напишешь, он будет иногда звать тебя по имени. Имя узнают сервер этого мира и ИИ, "
            + "который говорит за Верити; он может запомнить его в этом мире. В одиночной игре с локальной Ollama оно не покидает компьютер. "
            + "Пиши только имя, без фамилии.");

    private final Component message;
    private final String prefill;
    private final BiConsumer<Boolean, String> done;
    private MultiLineLabel text = MultiLineLabel.EMPTY, hint = MultiLineLabel.EMPTY;
    private EditBox name;
    private int top, labelY, hintY;

    ConsentScreen(String city, String prefill, BiConsumer<Boolean, String> done) {
        super(Component.literal("Верити хочет знать о тебе больше"));
        this.prefill = prefill;
        this.done = done;
        message = Component.literal("Когда он очень разозлится, он может пугать тем, что знает о твоём настоящем компьютере: "
                + "город по часовому поясу" + (city.isEmpty() ? "" : " («" + city + "»)") + ", названия открытых программ, имя учётной записи, время на часах, "
                + "размер экрана, видеокарту, названия других твоих миров. Ещё он может сменить заголовок окна игры, сделать снимок экрана игры, "
                + "показать ложный «вылет» и ложное достижение, оставить в папке игры файлы verity.txt (адрес компьютера в домашней сети и время, когда ты выходил) и «ПРОЧИТАЙ_МЕНЯ.txt», а после выхода из мира — надпись в главном меню.\n\n"
                + "Внутрь программ и файлов он не смотрит. Всё это остаётся на этом компьютере: на сервер уходят только город, названия программ "
                + "и имя, если напишешь его ниже. Но это будет видно на экране: не разрешай, если идёт стрим или запись.");
    }

    @Override
    protected void init() {
        text = MultiLineLabel.create(font, message, Math.min(width - 50, 520));
        hint = MultiLineLabel.create(font, HINT, Math.min(width - 50, 420));
        int total = 20 + text.getLineCount() * 9 + 12 + 12 + 20 + 6 + hint.getLineCount() * 9 + 10 + 20;
        top = Math.max(6, (height - total) / 2);
        labelY = top + 20 + text.getLineCount() * 9 + 12;
        String typed = name == null ? prefill : name.getValue();           // survive a window resize
        name = new EditBox(font, width / 2 - 100, labelY + 12, 200, 20, ASK_NAME);
        name.setMaxLength(20);
        name.setValue(typed);
        addRenderableWidget(name);
        hintY = labelY + 12 + 20 + 6;
        int by = Math.min(hintY + hint.getLineCount() * 9 + 10, height - 24);
        addRenderableWidget(Button.builder(Component.literal("Да, пусть пугает"), b -> done.accept(true, name.getValue())).bounds(width / 2 - 155, by, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Нет"), b -> done.accept(false, "")).bounds(width / 2 + 5, by, 150, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, top, 0xFFFFFF);
        text.renderCentered(g, width / 2, top + 20);
        g.drawCenteredString(font, ASK_NAME, width / 2, labelY, 0xFFEBCC05);
        hint.renderCentered(g, width / 2, hintY, 9, 0xA0A0A0);
    }

    /** Development harness: as if the player had typed this and pressed yes. */
    void submit(String typed) {
        name.setValue(typed);
        done.accept(true, name.getValue());
    }

    @Override
    public void onClose() {
        done.accept(false, "");
    }
}
