import { describe, expect, it } from 'vitest';
import { age, ago, formatDuration } from './time';

describe('formatDuration', () => {
  it('picks the two most useful units', () => {
    expect(formatDuration(5)).toBe('5s');
    expect(formatDuration(59)).toBe('59s');
    expect(formatDuration(60)).toBe('1m');
    expect(formatDuration(3600)).toBe('1h');
    expect(formatDuration(3600 + 12 * 60)).toBe('1h 12m');
    expect(formatDuration(2 * 86400 + 5 * 3600)).toBe('2d 5h');
    expect(formatDuration(-3)).toBe('0s');
  });
});

describe('ago / age', () => {
  const now = Date.parse('2026-11-08T12:00:00Z');
  it('says just now for very recent events', () => {
    expect(ago('2026-11-08T11:59:50Z', now)).toBe('just now');
    expect(ago('2026-11-08T11:55:00Z', now)).toBe('5m ago');
  });
  it('stops counting an incident’s age once it is resolved', () => {
    expect(age('2026-11-08T10:00:00Z', '2026-11-08T10:45:00Z', now)).toBe('45m');
    expect(age('2026-11-08T10:00:00Z', null, now)).toBe('2h');
  });
});
