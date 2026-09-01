package app.coomi;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shizuku UserService：以 shell（ADB，uid 2000）身份执行白名单命令。
 *
 * <p>官方 API 13.1.5 起 {@code Shizuku#newProcess} 已私有化并计划移除，
 * 执行命令的标准方式是 UserService —— 由 Shizuku 服务端以 shell/root 身份
 * fork 独立进程加载本类，本类内部用 {@link Runtime#exec} 运行命令。</p>
 *
 * <p>本类只依赖 android.os/java.io，不引用 Shizuku API（UserService 进程
 * 的 classpath 不保证包含完整应用依赖）。</p>
 *
 * <p>事务协议（与 ShizukuExec 客户端约定）：</p>
 * <ul>
 *   <li>{@link #CMD_EXEC}：data 携带命令字符串，reply 返回 stdout 文本。</li>
 *   <li>{@link #CMD_DESTROY}（16777115）：Shizuku 规范销毁码，退出进程。</li>
 * </ul>
 */
public final class ShellUserService extends Binder {

    public static final String DESCRIPTOR = "app.coomi.ShellUserService";

    /** 执行命令（data: String cmd；reply: String output）。 */
    public static final int CMD_EXEC = 1;
    /** Shizuku 用户服务销毁事务码（官方约定，勿改）。 */
    public static final int CMD_DESTROY = 16777115;

    public ShellUserService() {
        // Binder 自身非 IInterface；onTransact 里手动 enforceInterface 校验 token。
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        switch (code) {
            case CMD_DESTROY: {
                // Shizuku 要求服务自行退出进程。
                System.exit(0);
                return true;
            }
            case CMD_EXEC: {
                data.enforceInterface(DESCRIPTOR);
                String cmd = data.readString();
                String output = exec(cmd);
                reply.writeNoException();
                reply.writeString(output);
                return true;
            }
            default:
                return super.onTransact(code, data, reply, flags);
        }
    }

    /** 以 shell 身份执行命令并返回合并后的 stdout（trim）。 */
    private static String exec(String cmd) {
        if (cmd == null || cmd.isEmpty()) return "";
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            proc.waitFor();
            return out.toString().trim();
        } catch (Exception e) {
            return "error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** IBinder 接口（Binder 已实现，仅作显式声明）。 */
}
