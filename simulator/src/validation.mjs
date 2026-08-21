const ISO_INSTANT_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/;

function isLeapYear(year) {
  return year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
}

function daysInMonth(year, month) {
  if (month === 2) {
    return isLeapYear(year) ? 29 : 28;
  }

  return [4, 6, 9, 11].includes(month) ? 30 : 31;
}

export function parseIsoInstant(value, { maxFractionDigits = 9 } = {}) {
  if (typeof value !== "string") {
    return undefined;
  }

  const match = ISO_INSTANT_PATTERN.exec(value);
  if (match === null) {
    return undefined;
  }

  const [
    ,
    rawYear,
    rawMonth,
    rawDay,
    rawHour,
    rawMinute,
    rawSecond,
    fraction,
    zone,
  ] = match;
  const year = Number(rawYear);
  const month = Number(rawMonth);
  const day = Number(rawDay);
  const hour = Number(rawHour);
  const minute = Number(rawMinute);
  const second = Number(rawSecond);

  if (
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > daysInMonth(year, month) ||
    hour > 23 ||
    minute > 59 ||
    second > 59 ||
    (fraction !== undefined && fraction.length > maxFractionDigits)
  ) {
    return undefined;
  }

  if (zone !== "Z") {
    const offsetHour = Number(zone.slice(1, 3));
    const offsetMinute = Number(zone.slice(4, 6));
    if (offsetHour > 23 || offsetMinute > 59) {
      return undefined;
    }
  }

  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? new Date(timestamp) : undefined;
}

export function isIsoInstant(value) {
  return parseIsoInstant(value) !== undefined;
}
