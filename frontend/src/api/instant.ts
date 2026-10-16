const ISO_INSTANT_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/;
const NANOSECONDS_PER_MILLISECOND = 1_000_000n;

export function parseInstant(value: unknown): bigint | null {
  if (typeof value !== "string" || value.trim() !== value) {
    return null;
  }

  const match = ISO_INSTANT_PATTERN.exec(value);
  if (match === null) {
    return null;
  }

  const [, yearText, monthText, dayText, hourText, minuteText, secondText] =
    match;
  const fraction = match[7] ?? "";
  const offset = match[8];
  const year = Number(yearText);
  const month = Number(monthText);
  const day = Number(dayText);
  const hour = Number(hourText);
  const minute = Number(minuteText);
  const second = Number(secondText);
  const calendarInstant = new Date(0);
  calendarInstant.setUTCFullYear(year, month - 1, day);
  calendarInstant.setUTCHours(hour, minute, second, 0);

  if (
    calendarInstant.getUTCFullYear() !== year ||
    calendarInstant.getUTCMonth() !== month - 1 ||
    calendarInstant.getUTCDate() !== day ||
    calendarInstant.getUTCHours() !== hour ||
    calendarInstant.getUTCMinutes() !== minute ||
    calendarInstant.getUTCSeconds() !== second
  ) {
    return null;
  }

  let offsetMinutes = 0;
  if (offset !== "Z") {
    const offsetHours = Number(offset.slice(1, 3));
    const offsetMinutePart = Number(offset.slice(4, 6));
    if (
      offsetHours > 18 ||
      offsetMinutePart > 59 ||
      (offsetHours === 18 && offsetMinutePart !== 0)
    ) {
      return null;
    }
    offsetMinutes =
      (offsetHours * 60 + offsetMinutePart) * (offset.startsWith("+") ? 1 : -1);
  }

  return (
    BigInt(calendarInstant.getTime() - offsetMinutes * 60 * 1_000) *
      NANOSECONDS_PER_MILLISECOND +
    BigInt(fraction.padEnd(9, "0") || "0")
  );
}
