#define _GNU_SOURCE
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#define LIBC "/usr/lib/libc.so.6"
static double now(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec + t.tv_nsec / 1e9;
}
static void spin(unsigned long n) {
  volatile unsigned long x = 0;
  for (unsigned long i = 0; i < n; i++)
    x += i;
}
static void stats(int n) {
  struct stat s;
  for (int i = 0; i < n; i++)
    stat(LIBC, &s);
}
static int cpu(void) {
  unsigned c = 0;
  syscall(SYS_getcpu, &c, NULL, NULL);
  return c;
}
static void allowed(char *out, int n) {
  char p[64], l[256];
  snprintf(p, 64, "/proc/self/task/%ld/status", (long)syscall(SYS_gettid));
  FILE *f = fopen(p, "r");
  out[0] = 0;
  if (!f)
    return;
  while (fgets(l, 256, f))
    if (!strncmp(l, "Cpus_allowed_list:", 18)) {
      sscanf(l + 18, "%63s", out);
    }
  fclose(f);
  (void)n;
}
static pthread_barrier_t bar;
static int cpus[64];
static char masks[64][64];
static void *poolw(void *a) {
  long i = (long)a;
  stats(300);
  pthread_barrier_wait(&bar);
  spin(200000000);
  cpus[i] = cpu();
  spin(200000000);
  allowed(masks[i], 64);
  return 0;
}
static int pool(int n) {
  pthread_t t[64];
  pthread_barrier_init(&bar, 0, n + 1);
  for (long i = 0; i < n; i++)
    pthread_create(&t[i], 0, poolw, (void *)i);
  usleep(400000);
  double s = now();
  pthread_barrier_wait(&bar);
  for (int i = 0; i < n; i++)
    pthread_join(t[i], 0);
  double e = now();
  unsigned long m = 0;
  for (int i = 0; i < n; i++)
    m |= 1ul << cpus[i];
  printf("T pool n=%d wall=%.2fs distinct_cpus=%d mask0=%s\n", n, e - s, __builtin_popcountl(m), masks[0]);
  printf("pool ok\n");
  return 0;
}
static void *mixw(void *a) {
  (void)a;
  stats(50);
  for (int i = 0; i < 400; i++) {
    spin(2000000);
    stats(1);
  }
  return 0;
}
static int mixed(int n) {
  pthread_t t[64];
  double s = now();
  for (long i = 0; i < n; i++)
    pthread_create(&t[i], 0, mixw, 0);
  for (int i = 0; i < n; i++)
    pthread_join(t[i], 0);
  printf("T mixed n=%d wall=%.2fs\n", n, now() - s);
  printf("mixed ok\n");
  return 0;
}
static volatile int turn;
static void *pongw(void *a) {
  long me = (long)a;
  stats(300);
  for (int i = 0; i < 200000; i++) {
    while (__atomic_load_n(&turn, __ATOMIC_ACQUIRE) != me)
      ;
    __atomic_store_n(&turn, !me, __ATOMIC_RELEASE);
  }
  return 0;
}
static int pong(void) {
  pthread_t a, b;
  double s = now();
  pthread_create(&a, 0, pongw, (void *)0);
  pthread_create(&b, 0, pongw, (void *)1);
  pthread_join(a, 0);
  pthread_join(b, 0);
  printf("T pong wall=%.2fs\n", now() - s);
  printf("pong ok\n");
  return 0;
}
static int late(void) {
  char p[64], b[4096];
  snprintf(p, 64, "/proc/self/task/%ld/status", (long)syscall(SYS_gettid));
  FILE *f = fopen(p, "r");
  int fd = fileno(f);
  stats(400);
  spin(1500000000);
  int n = pread(fd, b, 4095, 0);
  b[n > 0 ? n : 0] = 0;
  char *l = strstr(b, "Cpus_allowed_list:");
  char m[64] = "?";
  if (l)
    sscanf(l + 18, "%63s", m);
  printf("T late mask=%s\n", m);
  printf("late ok\n");
  return 0;
}
static void *spinw(void *a) {
  (void)a;
  for (;;)
    spin(1000000);
}
static void *execw(void *a) {
  (void)a;
  stats(200);
  usleep(100000);
  char *v[] = {"/usr/bin/echo", "thread-exec ok", 0};
  execv(v[0], v);
  perror("execv");
  _exit(9);
}
static int threxec(void) {
  pthread_t t;
  for (int i = 0; i < 4; i++)
    pthread_create(&t, 0, spinw, 0);
  pthread_create(&t, 0, execw, 0);
  sleep(20);
  printf("threxec FAILED still here\n");
  return 1;
}
static void *shortw(void *a) {
  (void)a;
  stats(3);
  return 0;
}
static int storm(void) {
  int bad = 0;
  for (int r = 0; r < 3000; r++) {
    pthread_t t[4];
    for (int i = 0; i < 4; i++)
      if (pthread_create(&t[i], 0, shortw, 0))
        bad++;
    for (int i = 0; i < 4; i++)
      pthread_join(t[i], 0);
  }
  for (int r = 0; r < 400; r++) {
    pid_t p = fork();
    if (!p) {
      stats(5);
      _exit(7);
    }
    int s;
    waitpid(p, &s, 0);
    if (!WIFEXITED(s) || WEXITSTATUS(s) != 7)
      bad++;
  }
  for (int r = 0; r < 200; r++) {
    pid_t p = vfork();
    if (!p) {
      _exit(3);
    }
    int s;
    waitpid(p, &s, 0);
    if (WEXITSTATUS(s) != 3)
      bad++;
  }
  printf("storm bad=%d\n", bad);
  return bad != 0;
}
static int killer(void) {
  int bad = 0;
  for (int r = 0; r < 300; r++) {
    pid_t p = fork();
    if (!p) {
      for (;;)
        stats(50);
    }
    usleep(3000);
    kill(p, SIGKILL);
    int s;
    waitpid(p, &s, 0);
    if (!WIFSIGNALED(s) || WTERMSIG(s) != SIGKILL)
      bad++;
  }
  pid_t p = fork();
  if (!p) {
    for (;;)
      stats(20);
  }
  for (int r = 0; r < 200; r++) {
    kill(p, SIGSTOP);
    usleep(1000);
    kill(p, SIGCONT);
    usleep(1000);
  }
  kill(p, SIGKILL);
  int s;
  waitpid(p, &s, 0);
  if (!WIFSIGNALED(s))
    bad++;
  printf("killer bad=%d\n", bad);
  return bad != 0;
}
static void *idlew(void *a) {
  stats(100);
  pthread_barrier_wait(&bar);
  pthread_barrier_wait(&bar);
  (void)a;
  return 0;
}
static int affin(void) {
  cpu_set_t a, b;
  long r;
  CPU_ZERO(&a);
  stats(300);
  r = syscall(SYS_sched_getaffinity, 0, sizeof a, &a);
  printf("get rc_pos=%d count=%d\n", r > 0, CPU_COUNT(&a));
  errno = 0;
  r = syscall(SYS_sched_getaffinity, 0, sizeof a, (void *)8);
  printf("get badptr r=%ld errno=%d\n", r, errno);
  char small[1];
  errno = 0;
  r = syscall(SYS_sched_getaffinity, 0, 1, small);
  printf("get tiny r=%ld errno=%d\n", r, errno);
  char big[512];
  memset(big, 0x55, 512);
  r = syscall(SYS_sched_getaffinity, 0, 512, big);
  printf("get big bytes=%d tail=%02x\n", r > 0, (unsigned char)big[511]);
  errno = 0;
  r = syscall(SYS_sched_getaffinity, 999999, sizeof a, &a);
  printf("get nopid r=%ld errno=%d\n", r, errno);
  pthread_barrier_init(&bar, 0, 2);
  pthread_t t;
  pthread_create(&t, 0, idlew, 0);
  pthread_barrier_wait(&bar);
  CPU_ZERO(&b);
  r = syscall(SYS_sched_getaffinity, 0, sizeof b, &b);
  printf("get self2 count=%d\n", CPU_COUNT(&b));
  pthread_barrier_wait(&bar);
  pthread_join(t, 0);
  CPU_ZERO(&b);
  errno = 0;
  r = syscall(SYS_sched_setaffinity, 0, sizeof b, &b);
  printf("set empty r=%ld errno=%d\n", r, errno);
  errno = 0;
  r = syscall(SYS_sched_setaffinity, 999999, sizeof a, &a);
  printf("set nopid r=%ld errno=%d\n", r, errno);
  errno = 0;
  r = syscall(SYS_sched_setaffinity, 0, sizeof a, (void *)8);
  printf("set badptr r=%ld errno=%d\n", r, errno);
  CPU_ZERO(&b);
  CPU_SET(1, &b);
  r = sched_setaffinity(0, sizeof b, &b);
  stats(500);
  int c = cpu();
  cpu_set_t g;
  sched_getaffinity(0, sizeof g, &g);
  printf("set one r=%ld on=%d count=%d isset=%d\n", r, c, CPU_COUNT(&g), CPU_ISSET(1, &g));
  pid_t p = fork();
  if (!p) {
    stats(300);
    cpu_set_t k;
    sched_getaffinity(0, sizeof k, &k);
    printf("child inherits count=%d on=%d\n", CPU_COUNT(&k), cpu());
    fflush(stdout);
    _exit(0);
  }
  int s;
  waitpid(p, &s, 0);
  r = sched_setaffinity(0, sizeof a, &a);
  stats(300);
  CPU_ZERO(&g);
  sched_getaffinity(0, sizeof g, &g);
  printf("set back r=%ld count=%d\n", r, CPU_COUNT(&g));
  return 0;
}
int main(int c, char **v) {
  setvbuf(stdout, 0, _IOLBF, 0);
  const char *m = c > 1 ? v[1] : "";
  int n = c > 2 ? atoi(v[2]) : 8;
  if (!strcmp(m, "pool"))
    return pool(n);
  if (!strcmp(m, "mixed"))
    return mixed(n);
  if (!strcmp(m, "late"))
    return late();
  if (!strcmp(m, "pong"))
    return pong();
  if (!strcmp(m, "threxec"))
    return threxec();
  if (!strcmp(m, "storm"))
    return storm();
  if (!strcmp(m, "killer"))
    return killer();
  if (!strcmp(m, "affin"))
    return affin();
  return 2;
}
