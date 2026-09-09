export function SeatBadge({
  seatsRemaining,
  capacity,
}: {
  seatsRemaining: number;
  capacity: number;
}) {
  if (seatsRemaining <= 0) {
    return <span className="badge bg-zinc-200 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300">Full</span>;
  }
  // The last seat is the interesting one, so it gets its own colour.
  const style =
    seatsRemaining === 1
      ? "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300"
      : "bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-300";
  return (
    <span className={`badge ${style}`}>
      {seatsRemaining} of {capacity} seats left
    </span>
  );
}
