\newpage

# Branding

**Branding** changes how the installation looks. Only an Admin can save branding; every signed-in user sees the result after refreshing.

![Branding settings](images/branding.png){ width=100% }

## Open the settings

Sign in as an Admin, select **Settings** in the header, and open the **Branding** tab.

## Settings

* **Display name**: up to 80 characters
* **Header and footer color**
* **Heading color**
* **Button color**
* **Logo**
* **Background image**

Colors are `#RRGGBB` values. The logo and background can be any image the browser can read, of any size: the browser scales and compresses them before upload (the logo to at most 512 pixels as PNG, the background to at most 2560 pixels as JPEG) so they are stored at no more than 250 KB.

## Notes

* Branding is stored in the database as a single record.
* The Keycloak-hosted sign-in pages use the branding too: the display name and logo as the title above the login box, the header color for the page background, the heading color for the page heading, and the button color for buttons and links. Without a logo, the cisys logo is shown. The pages read the branding from cisys without signing in, through `/api/branding/public` (the name and colors only) and `/api/branding/logo`. The background image is not applied there.
* If cisys is not reachable at `http://localhost:8080` from the browser, set `CISYS_API_URL` for the Keycloak container so the sign-in pages can find it.
* Light and dark mode is chosen per user in the header and is remembered by the browser.
