import { ApiError, api } from "@/lib/api-server";
import { ErrorNotice } from "@/components/ErrorNotice";
import { RacePanel } from "@/components/RacePanel";

export default async function RaceDemoPage() {
  let classes;
  try {
    classes = await api.trialClasses();
  } catch (e) {
    const err = e as ApiError;
    return <ErrorNotice code={err.code} message={err.message} />;
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Last-seat race</h1>
        <p className="muted mt-1 text-sm">
          Fires N booking-and-payment attempts at one class simultaneously, all
          released from the same barrier, and shows every outcome side by side.
          Pick a class with one seat left to see the race the brief describes.
        </p>
      </div>
      <RacePanel classes={classes} />
    </div>
  );
}
