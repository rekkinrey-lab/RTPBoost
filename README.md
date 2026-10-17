# RTPBoost

Smooths out the CPU spike after `/rtp` (BetterRTP) and other long teleports by
raising each player's chunk send distance gradually instead of all at once.

Requires Paper 1.21.x and Java 21.

## Build with GitHub (no tools needed on your PC)

1. Create a new GitHub repository (any name, public or private).
2. Upload everything from this folder to it, including the hidden `.github` folder.
3. Open the **Actions** tab, then **Build RTPBoost**, then wait for the green check.
   (Run it by hand with **Run workflow** if it didn't start.)
4. Open the finished run, scroll to **Artifacts**, download **RTPBoost-jar**.
5. Unzip it and put `RTPBoost-1.0.0.jar` in your server's `plugins/` folder.
6. Restart the server. Check with `/rtpboost status`.

Settings are in `plugins/RTPBoost/config.yml`; use `/rtpboost reload` after editing.
