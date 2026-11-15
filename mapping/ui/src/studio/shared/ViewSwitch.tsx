/** A pane's view modes as pressed buttons (the prototype's ViewSwitch). */
export function ViewSwitch<M extends string>({ label, value, onChange, modes }: { label: string; value: M; onChange: (mode: M) => void; modes: readonly M[] }) {
  return (
    <div className="switches view-switch" role="group" aria-label={label}>
      {modes.map((mode) => (
        <button key={mode} type="button" aria-pressed={value === mode} onClick={() => onChange(mode)}>
          {mode}
        </button>
      ))}
    </div>
  );
}
