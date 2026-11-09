"""Daily hydration target calculation.

IMPORTANT: These are general-wellness heuristics, NOT medical advice.
Constants are deliberately configurable. Reference points used when choosing
defaults (verify against the primary sources before relying on them):
  * Mayo Clinic cites ~3.7 L/day total fluids for men, of which ~20% usually
    comes from food (so roughly 3 L from drinks).
  * The NHS Eatwell Guide suggests 6-8 cups/glasses of fluid a day as a guide,
    more when hot or active.
  * Sweat losses during exercise vary widely by person, intensity and climate;
    commonly quoted replacement ranges are roughly 400-800 ml per hour.
The cap exists to avoid ever encouraging over-drinking (hyponatremia risk).
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, time, timedelta


@dataclass(frozen=True)
class TargetConfig:
    ml_per_kg: float = 35.0
    fallback_weight_kg: float = 75.0
    ml_per_workout_hour: float = 500.0
    high_strain_threshold: float = 14.0  # WHOOP strain scale is 0-21
    high_strain_multiplier: float = 1.3
    min_target_ml: int = 1500
    max_target_ml: int = 4500
    max_single_entry_ml: int = 2000


@dataclass(frozen=True)
class Workout:
    start: datetime
    end: datetime
    strain: float | None = None

    @property
    def hours(self) -> float:
        seconds = (self.end - self.start).total_seconds()
        # Guard against bad/negative data and absurd durations (e.g. a workout
        # left running overnight).
        return max(0.0, min(seconds / 3600.0, 6.0))


def daily_target_ml(
    weight_kg: float | None,
    workouts: list[Workout],
    cfg: TargetConfig = TargetConfig(),
) -> int:
    """Return the daily drinking target in ml, rounded to the nearest 50 ml."""
    weight = weight_kg if weight_kg and 30 <= weight_kg <= 250 else cfg.fallback_weight_kg
    base = weight * cfg.ml_per_kg

    extra = 0.0
    for w in workouts:
        add = w.hours * cfg.ml_per_workout_hour
        if w.strain is not None and w.strain >= cfg.high_strain_threshold:
            add *= cfg.high_strain_multiplier
        extra += add

    total = base + extra
    total = max(cfg.min_target_ml, min(cfg.max_target_ml, total))
    return int(round(total / 50.0) * 50)


def expected_by_now_ml(
    target_ml: int,
    now: datetime,
    wake: time = time(7, 0),
    sleep: time = time(22, 30),
) -> int:
    """Linear pro-rating of the target across waking hours.

    Before wake -> 0; after sleep -> full target. Assumes wake < sleep on the
    same calendar day (late-night shift workers would need a different model;
    flagged in docs/LIMITATIONS).
    """
    day = now.date()
    start = datetime.combine(day, wake, tzinfo=now.tzinfo)
    end = datetime.combine(day, sleep, tzinfo=now.tzinfo)
    if end <= start:
        end += timedelta(days=1)
    if now <= start:
        return 0
    if now >= end:
        return target_ml
    frac = (now - start) / (end - start)
    return int(target_ml * frac)


def mood(consumed_ml: int, expected_ml: int, behind_ratio: float = 0.75) -> str:
    """Elephant mood: 'happy' (on track), 'neutral', or 'thirsty' (>25% behind)."""
    if expected_ml <= 0 or consumed_ml >= expected_ml:
        return "happy"
    if consumed_ml < expected_ml * behind_ratio:
        return "thirsty"
    return "neutral"
