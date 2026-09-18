# Page Override

## Screen

- Name: Privacy activity within Rules
- User goal: choose a microphone or camera signal, scope it to any app or one app, and set its light.

## Deviations From Master

- Keep this as a separate Rules section, not another value in notification/foreground triggers.
- Default microphone signal is red; camera is green. Text always names the activity.
- Show the 10-second light, 10-second cooldown, and one-minute cap together as one concise summary.

## Constraints

- Never imply the app reads recordings; it observes Android's active privacy operation in the
  privileged renderer.
- Cooldown means the session is released, not merely painted black.
- English and Japanese strings, accessible labels, and 48dp targets are required.
