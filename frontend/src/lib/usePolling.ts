import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Phase 5 keeps the dashboard fresh by polling; phase 6 replaces most of it
 * with server push (SSE) and keeps polling only as a fallback.
 *
 * Details that separate this from a naive setInterval(fetch):
 *  - No overlap: the next request is scheduled only after the previous one
 *    finishes, so a slow backend never gets a pile-up of concurrent polls.
 *  - Paused in background tabs (Page Visibility API), and refreshed the
 *    moment the tab becomes visible again. Ten idle tabs cost nothing.
 *  - Stale-while-revalidate: old data stays on screen during a refresh, so
 *    the table never flashes empty every few seconds.
 *  - AbortController: changing filters or leaving the page cancels the
 *    in-flight request, so a slow old response can't overwrite a newer one.
 */
export interface Polled<T> {
  data: T | undefined;
  error: Error | null;
  loading: boolean;      // true only until the first response
  lastUpdated: Date | null;
  refresh: () => void;   // fetch now (after a user action), then resume the schedule
}

export function usePolling<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  intervalMs: number,
  deps: unknown[],
): Polled<T> {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<Error | null>(null);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [tick, setTick] = useState(0);

  // "loading" = no response yet for THIS set of deps. Derived, not stored, so
  // switching filters shows loading again without an extra state update.
  const depsKey = JSON.stringify(deps);
  const [answeredKey, setAnsweredKey] = useState<string | null>(null);
  const loading = answeredKey !== depsKey;

  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let controller: AbortController | null = null;

    const run = async () => {
      if (document.visibilityState === 'hidden') return; // resumed by onVisible
      controller = new AbortController();
      try {
        const result = await fetcherRef.current(controller.signal);
        if (cancelled) return;
        setData(result);
        setError(null);
        setLastUpdated(new Date());
      } catch (e) {
        if (cancelled || (e instanceof DOMException && e.name === 'AbortError')) return;
        setError(e as Error);
      } finally {
        if (!cancelled) {
          setAnsweredKey(depsKey);
          timer = setTimeout(run, intervalMs);
        }
      }
    };

    const onVisible = () => {
      if (document.visibilityState === 'visible') {
        clearTimeout(timer);
        controller?.abort();
        run();
      }
    };

    run();
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      cancelled = true;
      clearTimeout(timer);
      controller?.abort();
      document.removeEventListener('visibilitychange', onVisible);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [depsKey, intervalMs, tick]);

  const refresh = useCallback(() => setTick((t) => t + 1), []);
  return { data, error, loading, lastUpdated, refresh };
}
