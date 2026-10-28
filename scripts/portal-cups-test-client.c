/* Test-only actual libcups job-state readback. CUPS_SERVER selects the fixture. */
#include <cups/cups.h>
#include <stdio.h>

int main(void) {
    cups_job_t *jobs = NULL;
    int count = cupsGetJobs(&jobs, "Android", 0, CUPS_WHICHJOBS_ALL);
    if (count < 0) { fprintf(stderr, "%s\n", cupsLastErrorString()); return 1; }
    for (int i = 0; i < count; i++)
        printf("%d %s\n", jobs[i].id, ippEnumString("job-state", jobs[i].state));
    cupsFreeJobs(count, jobs);
    return 0;
}
