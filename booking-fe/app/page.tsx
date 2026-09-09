import { api } from "@/lib/api-server";
import { ErrorNotice } from "@/components/ErrorNotice";
import { ApiError } from "@/lib/api-server";
import { selectParent } from "./actions";
import { currentParentId } from "@/lib/session";
import { RebuildDemoButton } from "@/components/RebuildDemoButton";

export default async function ParentPickerPage() {
  let parents;
  try {
    parents = await api.parents();
  } catch (e) {
    const err = e as ApiError;
    return <ErrorNotice code={err.code} message={err.message} />;
  }

  const current = await currentParentId();

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Book a trial class</h1>
        <p className="muted mt-1 text-sm">
          Choose which parent you are. This stands in for signing in - there is
          no auth in this demo.
        </p>
      </div>

      <form action={selectParent} className="card space-y-4">
        <div>
          <label htmlFor="parentId" className="label">
            Parent
          </label>
          <select
            id="parentId"
            name="parentId"
            className="select"
            defaultValue={current ?? parents[0]?.id}
          >
            {parents.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} ({p.email})
              </option>
            ))}
          </select>
        </div>
        <button type="submit" className="btn-primary">
          Continue
        </button>
      </form>

      <div className="card space-y-3">
        <div>
          <h2 className="font-medium">Demo data</h2>
          <p className="muted mt-1 text-sm">
            Bookings made during a walkthrough leave the fixtures used up.
            Rebuilding restores the four seeded classes - including the
            one-seat-left race fixture - and refreshes their start times.
          </p>
        </div>
        <RebuildDemoButton />
      </div>
    </div>
  );
}
