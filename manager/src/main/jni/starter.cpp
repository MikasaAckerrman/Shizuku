#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <ctime>
#include <cstring>
#include <libgen.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/resource.h>
#include <sys/time.h>
#include <sys/inotify.h>
#include <poll.h>
#include <cerrno>
#include <string>
#include <termios.h>
#include "android.h"
#include "misc.h"
#include "selinux.h"
#include "cgroup.h"
#include "logging.h"

#ifdef DEBUG
#define JAVA_DEBUGGABLE
#endif

#define perrorf(...) fprintf(stderr, __VA_ARGS__)
#define LOGPF(...) do { printf(__VA_ARGS__); fflush(stdout); } while(0)

#define EXIT_FATAL_SET_CLASSPATH 3
#define EXIT_FATAL_FORK 4
#define EXIT_FATAL_APP_PROCESS 5
#define EXIT_FATAL_UID 6
#define EXIT_FATAL_PM_PATH 7
#define EXIT_FATAL_KILL 9
#define EXIT_FATAL_BINDER_BLOCKED_BY_SELINUX 10

#define PACKAGE_NAME "moe.shizuku.privileged.api"
#define SERVER_NAME "shizuku_server"
#define SERVER_CLASS_PATH "rikka.shizuku.server.ShizukuService"

#if defined(__arm__)
#define ABI "arm"
#elif defined(__i386__)
#define ABI "x86"
#elif defined(__x86_64__)
#define ABI "x86_64"
#elif defined(__aarch64__)
#define ABI "arm64"
#endif

static bool read_ready_pid(const char *ready_file, int expected_pid) {
    FILE *fp = fopen(ready_file, "r");
    if (fp == nullptr) return false;
    int ready_pid = 0;
    int n = fscanf(fp, "%d", &ready_pid);
    fclose(fp);
    return n == 1 && ready_pid == expected_pid;
}

static bool wait_for_binder_ready(int pid, int timeout_ms, const char *ready_file) {
    // Fast path: already ready.
    if (read_ready_pid(ready_file, pid)) return true;

    // Use inotify for instant notification when the server writes the file.
    int inotify_fd = inotify_init1(IN_CLOEXEC);
    int watch_fd = -1;
    bool use_inotify = false;
    if (inotify_fd >= 0) {
        watch_fd = inotify_add_watch(inotify_fd, "/data/local/tmp",
                                     IN_CLOSE_WRITE | IN_MOVED_TO | IN_CREATE | IN_MODIFY);
        if (watch_fd >= 0) {
            use_inotify = true;
        }
    }

    int elapsed_ms = 0;
    const int poll_ms = 20;
    while (elapsed_ms < timeout_ms) {
        if (read_ready_pid(ready_file, pid)) {
            if (use_inotify) {
                inotify_rm_watch(inotify_fd, watch_fd);
                close(inotify_fd);
            }
            return true;
        }

        if (use_inotify) {
            struct pollfd pfd = { inotify_fd, POLLIN, 0 };
            int ret = poll(&pfd, 1, poll_ms);
            if (ret > 0 && (pfd.revents & POLLIN)) {
                char buf[1024];
                ssize_t n = read(inotify_fd, buf, sizeof(buf));
                (void)n;
                // Event consumed; next loop iteration will read the file.
                continue;
            }
        } else {
            usleep(poll_ms * 1000);
        }
        elapsed_ms += poll_ms;
    }

    if (use_inotify) {
        inotify_rm_watch(inotify_fd, watch_fd);
        close(inotify_fd);
    }
    return read_ready_pid(ready_file, pid);
}

static void run_server(const char *dex_path, const char *main_class, const char *process_name) {
    if (setenv("CLASSPATH", dex_path, true)) {
        LOGE("can't set CLASSPATH\n");
        exit(EXIT_FATAL_SET_CLASSPATH);
    }

#define ARG(v) char **v = nullptr; \
    char buf_##v[PATH_MAX]; \
    size_t v_size = 0; \
    uintptr_t v_current = 0;
#define ARG_PUSH(v, arg) v_size += sizeof(char *); \
if (v == nullptr) { \
    v = (char **) malloc(v_size); \
} else { \
    v = (char **) realloc(v, v_size);\
} \
v_current = (uintptr_t) v + v_size - sizeof(char *); \
*((char **) v_current) = arg ? strdup(arg) : nullptr;

#define ARG_END(v) ARG_PUSH(v, nullptr)

#define ARG_PUSH_FMT(v, fmt, ...) snprintf(buf_##v, PATH_MAX, fmt, __VA_ARGS__); \
    ARG_PUSH(v, buf_##v)

#ifdef JAVA_DEBUGGABLE
#define ARG_PUSH_DEBUG_ONLY(v, arg) ARG_PUSH(v, arg)
#define ARG_PUSH_DEBUG_VM_PARAMS(v) \
    if (android_get_device_api_level() >= 30) { \
        ARG_PUSH(v, "-Xcompiler-option"); \
        ARG_PUSH(v, "--debuggable"); \
        ARG_PUSH(v, "-XjdwpProvider:adbconnection"); \
        ARG_PUSH(v, "-XjdwpOptions:suspend=n,server=y"); \
    } else if (android_get_device_api_level() >= 28) { \
        ARG_PUSH(v, "-Xcompiler-option"); \
        ARG_PUSH(v, "--debuggable"); \
        ARG_PUSH(v, "-XjdwpProvider:internal"); \
        ARG_PUSH(v, "-XjdwpOptions:transport=dt_android_adb,suspend=n,server=y"); \
    } else { \
        ARG_PUSH(v, "-Xcompiler-option"); \
        ARG_PUSH(v, "--debuggable"); \
        ARG_PUSH(v, "-agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y"); \
    }
#else
#define ARG_PUSH_DEBUG_VM_PARAMS(v)
#define ARG_PUSH_DEBUG_ONLY(v, arg)
#endif

    char lib_path[PATH_MAX]{0};
    snprintf(lib_path, PATH_MAX, "%s/lib/%s", dirname(dex_path), ABI);

    ARG(argv)
    ARG_PUSH(argv, "/system/bin/app_process")
    ARG_PUSH_FMT(argv, "-Djava.class.path=%s", dex_path)
    ARG_PUSH_FMT(argv, "-Dshizuku.library.path=%s", lib_path)
    ARG_PUSH_DEBUG_VM_PARAMS(argv)
    ARG_PUSH(argv, "/system/bin")
    ARG_PUSH_FMT(argv, "--nice-name=%s", process_name)
    ARG_PUSH(argv, main_class)
    ARG_PUSH_DEBUG_ONLY(argv, "--debug")
    ARG_END(argv)

    LOGD("exec app_process");

    if (execvp((const char *) argv[0], argv)) {
        exit(EXIT_FATAL_APP_PROCESS);
    }
}

static void start_server(const char *path, const char *main_class, const char *process_name) {
    // [fix-22] Try to boost this process priority before forking. The child
    // inherits it, giving the server a better chance to run on a laggy
    // post-reboot system. This is best-effort and ignored if not allowed.
    if (setpriority(PRIO_PROCESS, 0, -10) != 0) {
        LOGD("setpriority(PRIO_PROCESS, 0, -10) failed, ignored");
    }

    pid_t pid = fork();
    switch (pid) {
        case -1: {
            perrorf("fatal: can't fork\n");
            exit(EXIT_FATAL_FORK);
        }
        case 0: {
            LOGD("child");
            setsid();
            chdir("/");
            int fd = open("/dev/null", O_RDWR);
            if (fd != -1) {
                dup2(fd, STDIN_FILENO);
                dup2(fd, STDOUT_FILENO);
                dup2(fd, STDERR_FILENO);
                if (fd > 2) close(fd);
            }
            run_server(path, main_class, process_name);
        }
        default: {
            LOGPF("info: shizuku_server pid is %d\n", pid);
            fflush(stdout);

            // [fix-19] Block until the server's binder is ready (or timeout).
            // This eliminates the need for callers to poll/retry and makes the
            // start a single synchronous operation. The server writes its pid
            // to /data/local/tmp/.shizuku_ready as soon as the binder is
            // registered; the file is on tmpfs and is cleared on reboot.
            static const int BINDER_READY_TIMEOUT_MS = 15000;
            static const char *READY_FILE = "/data/local/tmp/.shizuku_ready";

            bool ready = wait_for_binder_ready(pid, BINDER_READY_TIMEOUT_MS, READY_FILE);
            if (ready) {
                LOGPF("info: shizuku_starter exit with 0 (server ready)\n");
            } else {
                LOGPF("warning: server pid %d started but binder not ready in %d ms, exiting anyway\n",
                       pid, BINDER_READY_TIMEOUT_MS);
            }
            exit(EXIT_SUCCESS);
        }
    }
}

static int check_selinux(const char *s, const char *t, const char *c, const char *p) {
    int res = se::selinux_check_access(s, t, c, p, nullptr);
#ifndef DEBUG
    if (res != 0) {
#endif
    LOGPF("info: selinux_check_access %s %s %s %s: %d\n", s, t, c, p, res);
    fflush(stdout);
#ifndef DEBUG
    }
#endif
    return res;
}

static int switch_cgroup() {
    int pid = getpid();
    if (cgroup::switch_cgroup("/acct", pid)) {
        LOGPF("info: switch cgroup succeeded, cgroup in /acct\n");
        return 0;
    }
    if (cgroup::switch_cgroup("/dev/cg2_bpf", pid)) {
        LOGPF("info: switch cgroup succeeded, cgroup in /dev/cg2_bpf\n");
        return 0;
    }
    if (cgroup::switch_cgroup("/sys/fs/cgroup", pid)) {
        LOGPF("info: switch cgroup succeeded, cgroup in /sys/fs/cgroup\n");
        return 0;
    }
    char buf[PROP_VALUE_MAX + 1];
    if (__system_property_get("ro.config.per_app_memcg", buf) > 0 &&
        strncmp(buf, "false", 5) != 0) {
        if (cgroup::switch_cgroup("/dev/memcg/apps", pid)) {
            LOGPF("info: switch cgroup succeeded, cgroup in /dev/memcg/apps\n");
            return 0;
        }
    }
    LOGPF("warn: can't switch cgroup\n");
    fflush(stdout);
    return -1;
}

int main(int argc, char *argv[]) {
    std::string apk_path;
    for (int i = 0; i < argc; ++i) {
        if (strncmp(argv[i], "--apk=", 6) == 0) {
            apk_path = argv[i] + 6;
        }
    }

    uid_t uid = getuid();
    if (uid != 0 && uid != 2000) {
        perrorf("fatal: run Shizuku from non root nor adb user (uid=%d).\n", uid);
        exit(EXIT_FATAL_UID);
    }

    se::init();

    if (uid == 0) {
        switch_cgroup();

        if (android_get_device_api_level() >= 29) {
            LOGPF("info: switching mount namespace to init...\n");
            switch_mnt_ns(1);
        }
    }

    if (uid == 0) {
        char *context = nullptr;
        if (se::getcon(&context) == 0) {
            int res = 0;

            res |= check_selinux("u:r:untrusted_app:s0", context, "binder", "call");
            res |= check_selinux("u:r:untrusted_app:s0", context, "binder", "transfer");

            if (res != 0) {
                perrorf("fatal: the su you are using does not allow app (u:r:untrusted_app:s0) to connect to su (%s) with binder.\n",
                        context);
                exit(EXIT_FATAL_BINDER_BLOCKED_BY_SELINUX);
            }
            se::freecon(context);
        }
    }

    LOGPF("info: starter begin\n");
    fflush(stdout);

    // [fix-12] Idempotent start: if a healthy server is already running,
    // DON'T kill it. Previously EVERY starter invocation SIGKILLed all
    // shizuku_server processes before forking a new one — any second
    // session, receiver re-delivery, or accidental re-run would destroy
    // a working server and disconnect all active clients.
    //
    // Health signal: the server writes its pid to
    // /data/local/tmp/.shizuku_ready as soon as its binder is registered.
    // If that file exists, the pid is alive, and the process is actually
    // shizuku_server, treat the server as healthy.
    {
        int server_pid = 0;
        FILE *fp = fopen("/data/local/tmp/.shizuku_ready", "r");
        if (fp != nullptr) {
            if (fscanf(fp, "%d", &server_pid) != 1) server_pid = 0;
            fclose(fp);
        }
        if (server_pid <= 0) {
            char pid_str[32] = {0};
            __system_property_get("shizuku.server.pid", pid_str);
            if (pid_str[0] != '\0') server_pid = atoi(pid_str);
        }
        if (server_pid > 0 && server_pid != getpid()) {
            char name[1024];
            if (get_proc_name(server_pid, name, 1024) == 0 &&
                strcmp(SERVER_NAME, name) == 0) {
                LOGPF("info: healthy server already running (pid %d), skipping start\n", server_pid);
                fflush(stdout);
                exit(EXIT_SUCCESS);
            }
        }
    }

    // kill old server (only reached when no healthy server is registered)
    LOGPF("info: killing old process...\n");
    fflush(stdout);

    foreach_proc([](pid_t pid) {
        if (pid == getpid()) return;

        char name[1024];
        if (get_proc_name(pid, name, 1024) != 0) return;

        if (strcmp(SERVER_NAME, name) != 0)
            return;

        if (kill(pid, SIGKILL) == 0)
            LOGPF("info: killed %d (%s)\n", pid, name);
        else if (errno == EPERM) {
            perrorf("fatal: can't kill %d, please try to stop existing Shizuku from app first.\n", pid);
            exit(EXIT_FATAL_KILL);
        } else {
            LOGPF("warn: failed to kill %d (%s)\n", pid, name);
        }
    });

    if (access(apk_path.c_str(), R_OK) == 0) {
        LOGPF("info: use apk path from argv\n");
        fflush(stdout);
    }

    if (apk_path.empty()) {
        auto f = popen("pm path " PACKAGE_NAME, "r");
        if (f) {
            char line[PATH_MAX]{0};
            fgets(line, PATH_MAX, f);
            trim(line);
            if (strstr(line, "package:") == line) {
                apk_path = line + strlen("package:");
            }
            pclose(f);
        }
    }

    if (apk_path.empty()) {
        perrorf("fatal: can't get path of manager\n");
        exit(EXIT_FATAL_PM_PATH);
    }

    LOGPF("info: apk path is %s\n", apk_path.c_str());
    if (access(apk_path.c_str(), R_OK) != 0) {
        perrorf("fatal: can't access manager %s\n", apk_path.c_str());
        exit(EXIT_FATAL_PM_PATH);
    }

    LOGPF("info: starting server...\n");
    fflush(stdout);
    LOGD("start_server");
    start_server(apk_path.c_str(), SERVER_CLASS_PATH, SERVER_NAME);
}
