import Link from "next/link";
import { ApiError, api, formatClassTime } from "@/lib/api-server";
import { BookingForm } from "@/components/BookingForm";
import { SeatBadge } from "@/components/SeatBadge";
import { ErrorNotice } from "@/components/ErrorNotice";
import { currentParentId } from "@/lib/session";
import type { Student, TrialClass } from "@/lib/types";

const TRIAL_PRICE_CENTS = 4900;

export default async function BookPage({ params }: PageProps<"/book/[classId]">) {
  const { classId } = await params;
  const parentId = await currentParentId();

  if (!parentId) {
    return (
      <div className="card text-sm">
        No parent selected.{" "}
        <Link href="/" className="underline">
          Choose one first
        </Link>
        .
      </div>
    );
  }

  // Only the fetch belongs in the try. JSX built inside it would render after
  // the catch is gone, so a render error would escape unhandled.
  let trialClass: TrialClass;
  let students: Student[];
  try {
    [trialClass, students] = await Promise.all([
      api.trialClass(Number(classId)),
      api.students(parentId, Number(classId)),
    ]);
  } catch (e) {
    const err = e as ApiError;
    return <ErrorNotice code={err.code} message={err.message} />;
  }

  return (
    <div className="space-y-6">
      <div>
        <Link href="/classes" className="muted text-sm hover:underline">
          &larr; All classes
        </Link>
        <h1 className="mt-2 text-2xl font-semibold">{trialClass.subject}</h1>
        <p className="muted mt-1 text-sm">{formatClassTime(trialClass.startsAt)}</p>
        <div className="mt-2">
          <SeatBadge
            seatsRemaining={trialClass.seatsRemaining}
            capacity={trialClass.capacity}
          />
        </div>
      </div>

      <BookingForm
        trialClassId={trialClass.id}
        students={students}
        priceCents={TRIAL_PRICE_CENTS}
      />
    </div>
  );
}
