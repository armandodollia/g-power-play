package local.handheld.tweaks;

interface IShellService {
    void destroy() = 16777114;
    String exec(String cmd) = 1;
}
