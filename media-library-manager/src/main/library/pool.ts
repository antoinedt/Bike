/**
 * Runs `work` over a growing queue of tasks with at most `concurrency` in flight. `work` can add
 * more tasks with `push` (a directory walker pushes the subfolders it finds). A task that throws
 * is skipped — one unreadable folder never stops the rest of a scan. Resolves when the queue is
 * empty and nothing is running.
 */
export function runPool<T>(initial: T[], concurrency: number, work: (task: T, push: (task: T) => void) => Promise<void>): Promise<void> {
  const queue = [...initial];
  let active = 0;
  return new Promise((resolve) => {
    const pump = () => {
      if (queue.length === 0 && active === 0) {
        resolve();
        return;
      }
      while (active < concurrency && queue.length > 0) {
        const task = queue.shift() as T;
        active++;
        work(task, (next) => queue.push(next))
          .catch(() => undefined)
          .finally(() => {
            active--;
            pump();
          });
      }
    };
    pump();
  });
}
