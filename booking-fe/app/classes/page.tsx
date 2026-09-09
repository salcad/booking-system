import Link from "next/link";
import { ApiError, api } from "@/lib/api-server";
import { ClassCard } from "@/components/ClassCard";
import { AutoRefresh } from "@/components/AutoRefresh";
import { ErrorNotice } from "@/components/ErrorNotice";
import { currentParentId } from "@/lib/session";

export default async function ClassesPage() {
  const parentId = await currentParentId();

  let classes;
  try {
    classes = await api.trialClasses();
  } catch (e) {
    const err = e as ApiError;
    return <ErrorNotice code={err.code} message={err.message} />;
  }

  return (
    <div className="space-y-6">
      <AutoRefresh />

      <div className="flex items-baseline justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Trial classes</h1>
          <p className="muted mt-1 text-sm">
            Seat counts refresh every 5 seconds. Every class caps at 4 students.
          </p>
        </div>
        {!parentId && (
          <Link href="/" className="text-sm underline">
            Pick a parent
          </Link>
        )}
      </div>

      {!parentId && (
        <div className="card text-sm">
          No parent selected.{" "}
          <Link href="/" className="underline">
            Choose one first
          </Link>{" "}
          so the booking form knows whose children to list.
        </div>
      )}

      <div className="space-y-3">
        {classes.map((c) => (
          <ClassCard key={c.id} trialClass={c} />
        ))}
      </div>
    </div>
  );
}
