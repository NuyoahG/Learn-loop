import { useId } from "react";

const colors = [["LEMON", "奶油黄"], ["MINT", "薄荷绿"], ["SKY", "浅天蓝"], ["LILAC", "香芋紫"], ["PEACH", "蜜桃粉"]];

export function HighlightColors({ color, onChange, disabled }) {
    const group = useId();
    return <fieldset className="color-picker" disabled={disabled}>
        <legend>高亮颜色</legend>
        {colors.map(([value, label]) => (
            <label key={value} className="color-choice" data-color={value} title={label}>
                <input type="radio" name={group} value={value} checked={color === value}
                    aria-label={label} onChange={() => onChange(value)} />
                <span aria-hidden="true">{color === value ? "✓" : ""}</span>
            </label>
        ))}
    </fieldset>;
}
