/*
 * Hand-authored SVG rather than a diagramming library: these figures never
 * change, so shipping a renderer to the browser to draw them would be paying
 * megabytes for markup we can write once. Strokes and text use currentColor so
 * both themes are covered for free; the one literal hue is --accent, reserved
 * for the step each figure actually turns on.
 */

const LABEL = 10.5;

function Arrowheads({ id }: { id: string }) {
  return (
    <defs>
      <marker
        id={`${id}-head`}
        viewBox="0 0 10 10"
        refX="9"
        refY="5"
        markerWidth="6"
        markerHeight="6"
        orient="auto-start-reverse"
      >
        <path d="M0,0 L10,5 L0,10 z" fill="currentColor" />
      </marker>
      <marker
        id={`${id}-head-hot`}
        viewBox="0 0 10 10"
        refX="9"
        refY="5"
        markerWidth="6"
        markerHeight="6"
        orient="auto-start-reverse"
      >
        <path d="M0,0 L10,5 L0,10 z" fill="var(--accent)" />
      </marker>
    </defs>
  );
}

function Participant({ x, y, label }: { x: number; y: number; label: string }) {
  return (
    <>
      <rect
        x={x - 78}
        y={y}
        width={156}
        height={28}
        rx={3}
        fill="var(--card)"
        stroke="currentColor"
        strokeOpacity={0.35}
      />
      <text
        x={x}
        y={y + 18}
        textAnchor="middle"
        fontSize={11}
        fontWeight={600}
        fill="currentColor"
      >
        {label}
      </text>
    </>
  );
}

function Lifeline({ x, from, to }: { x: number; from: number; to: number }) {
  return (
    <line
      x1={x}
      y1={from}
      x2={x}
      y2={to}
      stroke="currentColor"
      strokeOpacity={0.25}
      strokeDasharray="3 4"
    />
  );
}

function Message({
  id,
  from,
  to,
  y,
  label,
  hot,
  dashed,
}: {
  id: string;
  from: number;
  to: number;
  y: number;
  label: string;
  hot?: boolean;
  dashed?: boolean;
}) {
  const dir = to > from ? 1 : -1;
  const stroke = hot ? "var(--accent)" : "currentColor";
  return (
    <>
      <text
        x={(from + to) / 2}
        y={y - 6}
        textAnchor="middle"
        fontSize={LABEL}
        fill={hot ? "var(--accent)" : "currentColor"}
        fillOpacity={hot ? 1 : 0.85}
      >
        {label}
      </text>
      <line
        x1={from}
        y1={y}
        x2={to - dir * 6}
        y2={y}
        stroke={stroke}
        strokeWidth={hot ? 1.6 : 1.2}
        strokeDasharray={dashed ? "4 3" : undefined}
        markerEnd={`url(#${id}-head${hot ? "-hot" : ""})`}
      />
    </>
  );
}

/* ------------------------------------------------------------------ */

export function RaceSequenceFigure() {
  const id = "race";
  const T1 = 95;
  const PG = 350;
  const T2 = 605;

  return (
    <figure className="space-y-3">
      <div className="overflow-x-auto rounded-lg border p-3" style={{ borderColor: "var(--border)" }}>
        <svg
          viewBox="0 0 700 352"
          role="img"
          aria-label="Two transactions race for the last seat; the second one blocks on the row lock, then re-evaluates its WHERE clause and matches nothing."
          className="h-auto w-full min-w-[560px]"
        >
          <Arrowheads id={id} />

          <Participant x={T1} y={8} label="T1 · Parent A" />
          <Participant x={PG} y={8} label="Postgres" />
          <Participant x={T2} y={8} label="T2 · Parent B" />

          <Lifeline x={T1} from={36} to={304} />
          <Lifeline x={PG} from={36} to={304} />
          <Lifeline x={T2} from={36} to={304} />

          <Message id={id} from={T1} to={PG} y={62} label="BEGIN" />
          <Message id={id} from={T2} to={PG} y={92} label="BEGIN" />
          <Message id={id} from={T1} to={PG} y={130} label="UPDATE … WHERE not full" />
          <Message id={id} from={PG} to={T1} y={158} label="1 row · class row locked" hot dashed />
          <Message id={id} from={T2} to={PG} y={196} label="the very same UPDATE" />

          <text x={477} y={226} textAnchor="middle" fontSize={LABEL} fill="var(--accent)">
            blocks on the row lock — does not fail
          </text>

          <Message id={id} from={T1} to={PG} y={262} label="INSERT booking · COMMIT" />
          <Message id={id} from={PG} to={T2} y={294} label="WHERE re-evaluated → 0 rows" hot dashed />

          <text x={T2} y={326} textAnchor="middle" fontSize={LABEL} fill="currentColor" fillOpacity={0.85}>
            409 CLASS_FULL · never charged
          </text>
        </svg>
      </div>
      <figcaption className="muted text-sm">
        <span className="font-medium" style={{ color: "var(--foreground)" }}>
          Everything turns on the second-to-last step.
        </span>{" "}
        T2 does not reuse the value it read earlier. Once the lock is released it re-reads the
        row and re-evaluates the <code className="font-mono text-xs">WHERE</code> clause against
        the newly committed value, so it matches nothing.
      </figcaption>
    </figure>
  );
}

/* ------------------------------------------------------------------ */

export function LifecycleFigure() {
  const id = "life";

  const box = (x: number, y: number, w: number, h: number, label: string, hot?: boolean) => (
    <>
      <rect
        x={x}
        y={y}
        width={w}
        height={h}
        rx={3}
        fill="var(--card)"
        stroke={hot ? "var(--accent)" : "currentColor"}
        strokeOpacity={hot ? 1 : 0.35}
      />
      <text
        x={x + w / 2}
        y={y + h / 2 + 4}
        textAnchor="middle"
        fontSize={11}
        fontWeight={600}
        fontFamily="var(--font-geist-mono), monospace"
        fill="currentColor"
      >
        {label}
      </text>
    </>
  );

  return (
    <figure className="space-y-3">
      <div className="overflow-x-auto rounded-lg border p-3" style={{ borderColor: "var(--border)" }}>
        <svg
          viewBox="0 0 700 264"
          role="img"
          aria-label="Booking lifecycle: a held booking can be confirmed, declined, cancelled or expired, and an expired booking can return to pending when a late payment reclaims the seat."
          className="h-auto w-full min-w-[560px]"
        >
          <Arrowheads id={id} />

          <circle cx={26} cy={60} r={5} fill="currentColor" />
          <line x1={34} y1={60} x2={52} y2={60} stroke="currentColor" strokeWidth={1.2} markerEnd={`url(#${id}-head)`} />

          {box(60, 42, 180, 36, "PENDING_PAYMENT")}
          {box(440, 10, 212, 34, "CONFIRMED")}
          {box(440, 64, 212, 34, "PAYMENT_FAILED")}
          {box(440, 118, 212, 34, "CANCELLED")}
          {box(60, 200, 180, 36, "EXPIRED", true)}

          <line x1={242} y1={56} x2={432} y2={29} stroke="currentColor" strokeWidth={1.2} markerEnd={`url(#${id}-head)`} />
          <text x={330} y={34} textAnchor="middle" fontSize={10} fill="currentColor" fillOpacity={0.85}>
            payment approved
          </text>

          <line x1={242} y1={62} x2={432} y2={80} stroke="currentColor" strokeWidth={1.2} markerEnd={`url(#${id}-head)`} />
          <text x={330} y={64} textAnchor="middle" fontSize={10} fill="currentColor" fillOpacity={0.85}>
            declined · seat released
          </text>

          <line x1={242} y1={70} x2={432} y2={134} stroke="currentColor" strokeWidth={1.2} markerEnd={`url(#${id}-head)`} />
          <text x={330} y={95} textAnchor="middle" fontSize={10} fill="currentColor" fillOpacity={0.85}>
            cancelled · seat released
          </text>

          <line x1={118} y1={80} x2={118} y2={196} stroke="currentColor" strokeWidth={1.2} markerEnd={`url(#${id}-head)`} />
          <line x1={182} y1={198} x2={182} y2={82} stroke="var(--accent)" strokeWidth={1.6} markerEnd={`url(#${id}-head-hot)`} />

          <text x={204} y={128} fontSize={10} fill="currentColor" fillOpacity={0.85}>
            reaper sweeps · seat released
          </text>
          <text x={204} y={148} fontSize={10} fill="var(--accent)">
            late payment reclaims the seat
          </text>
        </svg>
      </div>
      <figcaption className="muted text-sm">
        Only <code className="font-mono text-xs">PENDING_PAYMENT</code> and{" "}
        <code className="font-mono text-xs">CONFIRMED</code> count as live, and only those two
        are covered by the partial unique index — which is exactly why a declined payment may be
        retried: the status has left the index&rsquo;s reach.{" "}
        <span className="font-medium" style={{ color: "var(--foreground)" }}>
          The single edge that runs backwards
        </span>{" "}
        is the one that makes a late payment safe.
      </figcaption>
    </figure>
  );
}

/* ------------------------------------------------------------------ */

export function ReaperFigure() {
  const id = "reap";
  const PAY = 95;
  const PG = 350;
  const RP = 605;

  return (
    <figure className="space-y-3">
      <div className="overflow-x-auto rounded-lg border p-3" style={{ borderColor: "var(--border)" }}>
        <svg
          viewBox="0 0 700 236"
          role="img"
          aria-label="The reaper's SELECT uses SKIP LOCKED, so the booking row held by the payment path is skipped rather than expired."
          className="h-auto w-full min-w-[560px]"
        >
          <Arrowheads id={id} />

          <Participant x={PAY} y={8} label="PaymentService" />
          <Participant x={PG} y={8} label="Postgres" />
          <Participant x={RP} y={8} label="HoldReaper" />

          <Lifeline x={PAY} from={36} to={212} />
          <Lifeline x={PG} from={36} to={212} />
          <Lifeline x={RP} from={36} to={212} />

          <Message id={id} from={PAY} to={PG} y={66} label="SELECT booking … FOR UPDATE" />

          <rect x={247} y={82} width={206} height={22} rx={3} fill="var(--card)" stroke="var(--accent)" />
          <text x={350} y={97} textAnchor="middle" fontSize={LABEL} fill="var(--accent)">
            booking row held for this txn
          </text>

          <Message id={id} from={RP} to={PG} y={140} label="SELECT expired … SKIP LOCKED" />
          <Message id={id} from={PG} to={RP} y={172} label="locked row skipped" hot dashed />
          <Message id={id} from={PAY} to={PG} y={206} label="UPDATE CONFIRMED · COMMIT" />
        </svg>
      </div>
      <figcaption className="muted text-sm">
        <span className="font-medium" style={{ color: "var(--foreground)" }}>
          <code className="font-mono text-xs">SKIP LOCKED</code> is what settles this, not a queue.
        </span>{" "}
        A locked row means someone is paying, so the reaper steps over it instead of waiting. The
        transition is conditional as a second belt: only the caller that genuinely performed it
        releases the seat.
      </figcaption>
    </figure>
  );
}
