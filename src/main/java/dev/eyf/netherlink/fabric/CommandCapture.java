package dev.eyf.netherlink.fabric;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;

/**
 * 指令输出收集器：冒充「执行指令的那个来源」。
 *
 * <p>原版指令的反馈最终都走 {@code CommandSource.sendSystemMessage(Component)}，
 * 所以把它换成这个收集器，就能把 {@code give} / {@code list} / {@code time set day}
 * 这类指令的输出文本抓下来回传给 AstrBot。
 *
 * <p>用法：
 * <pre>{@code
 * var source = server.createCommandSourceStack()
 *         .withSource(capture)                                  // 换掉输出去向
 *         .withPermission(PermissionSet.ALL_PERMISSIONS);       // 权限等同控制台
 * server.getCommands().performPrefixedCommand(source, cmd);
 * }</pre>
 *
 * <p>⚠️ **与 Paper 端的差别**：Paper 的 {@code Bukkit.dispatchCommand} 会返回
 * boolean 表示成功与否；原版的 {@code performPrefixedCommand} 返回 **void**。
 * 所以「成功没有」只能靠**有没有捕获到失败反馈**来推断——见 {@link #sawFailure}。
 * 这是本实现里最需要实机验证的一点。
 */
final class CommandCapture implements CommandSource {

    private final StringBuilder out = new StringBuilder();
    private boolean failure = false;

    /** 累积的输出文本（各行以换行分隔）。 */
    String text() {
        return out.toString();
    }

    /**
     * 本次执行中是否出现过**失败**反馈。
     *
     * <p>原版对失败有两种呈现：{@code CommandSourceStack.sendFailure(...)}
     * （走的就是 {@code sendSystemMessage}），以及抛异常（调用方 catch）。
     * 这里只能看到前者——后者由 {@code runCommand} 的 catch 处理。
     */
    boolean sawFailure() {
        return failure;
    }

    @Override
    public void sendSystemMessage(Component message) {
        String s = message.getString();
        if (!s.isEmpty()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(s);
        }
    }

    /**
     * 是否接受成功反馈。
     *
     * <p>返回 true：{@code CommandSourceStack.sendSuccess} 会据此决定要不要把
     * 成功消息转给本 Source。若返回 false，正常指令（如 {@code time set day}）的
     * 输出就一条都收不到。
     */
    @Override
    public boolean acceptsSuccess() {
        return true;
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public boolean shouldInformAdmins() {
        // 冒充控制台执行，但对管理员而言不需要额外播报——输出已经回传给 AstrBot，
        // 由它决定怎么呈现。返回 false 与「控制台执行」的语义一致。
        return false;
    }
}
