"use strict";

const CONFIG = Object.freeze({
    apiUrl: "https://oawcto1611.execute-api.us-east-1.amazonaws.com",
    domain: "dev-wp2gou3ohfywb27y.us.auth0.com",
    clientId: "wouXwy5NIdOiSJWLZXxo9fR0VWxTnW6P",
    audience: "https://campus-cafe-api.com",
    redirectUri: "http://127.0.0.1:5500/frontend/index.html",
    scope: "openid profile email orders:create orders:read orders:cancel"
});

let authClient;
let currentUser = null;
let busy = false;

// Conserva la clave y el cuerpo del envío mientras esta página siga abierta.
// Esto NO sustituye la idempotencia que todavía falta implementar en Java.
let pendingSubmission = null;

const element = id => document.getElementById(id);

function showMessage(message) {
    element("result").textContent = message;
}

function showResponse(method, path, status, data) {
    element("result").textContent =
        `${method} ${path}\nHTTP ${status}\n\n` +
        JSON.stringify(data, null, 2);
}

function updateButtons() {
    const signedIn = currentUser !== null;

    element("login").disabled = !authClient || busy;
    element("logout").disabled = !signedIn || busy;
    element("create-order").disabled = !signedIn || busy;
    element("get-order").disabled = !signedIn || busy;
    element("cancel-order").disabled = !signedIn || busy;
}

async function refreshSession() {
    const authenticated = await authClient.isAuthenticated();
    currentUser = authenticated ? await authClient.getUser() : null;

    element("session-status").textContent = currentUser
        ? `Sesión autenticada: ${currentUser.email || currentUser.name || "Usuario"}`
        : "Sin sesión. Puedes consultar el catálogo sin iniciar sesión.";

    updateButtons();
}

function explainError(error) {
    // No imprimimos el objeto completo: podría incluir datos de autenticación.
    const code = error && error.error;

    if (code === "login_required" || code === "consent_required") {
        return "Inicia sesión de nuevo para autorizar el acceso a la API.";
    }

    return "No se pudo completar la operación. Comprueba la conexión y la " +
        "configuración de Auth0. Si ocurre durante el login, revisa también " +
        "las URLs permitidas y los permisos del usuario.";
}

async function runProtectedAction(action) {
    if (busy) return;

    busy = true;
    updateButtons();

    try {
        await action();
    } catch (error) {
        showMessage(explainError(error));
    } finally {
        busy = false;
        updateButtons();
    }
}

async function apiRequest(path, options = {}, protectedRoute = false) {
    const headers = new Headers(options.headers || {});

    if (protectedRoute) {
        if (!authClient || !await authClient.isAuthenticated()) {
            throw { error: "login_required" };
        }

        const accessToken = await authClient.getTokenSilently({
            authorizationParams: {
                audience: CONFIG.audience,
                scope: CONFIG.scope
            }
        });

        headers.set("Authorization", `Bearer ${accessToken}`);
    }

    // Las rutas utilizadas son internas y la URL base es fija.
    // El token se envía únicamente a esta API.
    const response = await fetch(CONFIG.apiUrl + path, {
        ...options,
        headers,
        redirect: "error"
    });

    const text = await response.text();
    let data;

    try {
        data = text ? JSON.parse(text) : {};
    } catch {
        data = { message: "El servidor devolvió una respuesta no JSON." };
    }

    return { response, data };
}

async function loadProducts() {
    element("products").textContent = "Cargando productos...";

    try {
        const { response, data } = await apiRequest("/products");

        if (!response.ok) {
            element("products").textContent = "No se pudo cargar el catálogo.";
            showResponse("GET", "/products", response.status, data);
            return;
        }

        if (!Array.isArray(data.items)) {
            element("products").textContent =
                "La respuesta no contiene la lista de productos esperada.";
            return;
        }

        const container = element("products");
        container.replaceChildren();

        for (const product of data.items) {
            const card = document.createElement("div");
            card.className = "product";

            const description = document.createElement("p");
            description.textContent =
                `${product.name} — ${(product.priceCents / 100).toFixed(2)} ` +
                `${product.currency}`;

            const button = document.createElement("button");
            button.textContent = product.available ? "Seleccionar" : "No disponible";
            button.disabled = !product.available;

            button.addEventListener("click", () => {
                element("product-id").value = product.productId;
            });

            card.append(description, button);
            container.append(card);
        }
    } catch {
        element("products").textContent =
            "No se pudo conectar con el catálogo. Revisa la API y CORS.";
    }
}

async function createOrder() {
    const productId = element("product-id").value.trim();
    const quantity = Number(element("quantity").value);

    if (!productId || !Number.isInteger(quantity) || quantity < 1 || quantity > 10) {
        showMessage("Selecciona un producto y una cantidad entera entre 1 y 10.");
        return;
    }

    const body = JSON.stringify({
        items: [{ productId, quantity }]
    });

    const principal = JSON.stringify([CONFIG.domain, currentUser.sub]);

    if (pendingSubmission &&
        (pendingSubmission.body !== body || pendingSubmission.principal !== principal)) {
        showMessage(
            "Hay un envío pendiente con otros datos. No se reutilizará su clave " +
            "para una orden diferente. Primero revisa el resultado de ese envío."
        );
        return;
    }

    if (!pendingSubmission) {
        pendingSubmission = {
            key: crypto.randomUUID(),
            body,
            principal
        };
    }

    showMessage("Enviando orden a AWS...");

    const { response, data } = await apiRequest("/orders", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "Idempotency-Key": pendingSubmission.key
        },
        body: pendingSubmission.body
    }, true);

    showResponse("POST", "/orders", response.status, data);

    if (response.ok && data.orderId) {
        element("order-id").value = data.orderId;
        pendingSubmission = null;
    } else if (response.status === 400) {
        // Solicitud rechazada por validación: permite corregir el siguiente envío.
        pendingSubmission = null;
    }
}

async function getOrder() {
    const orderId = element("order-id").value.trim();

    if (!orderId) {
        showMessage("Introduce el ID de la orden.");
        return;
    }

    const path = `/orders/${encodeURIComponent(orderId)}`;
    const { response, data } = await apiRequest(path, {}, true);

    showResponse("GET", path, response.status, data);
}
async function cancelOrder() {
    const orderId = element("order-id").value.trim();

    if (!orderId) {
        showMessage("Introduce el ID de la orden que quieres cancelar.");
        return;
    }

    const confirmed = window.confirm(
        `¿Quieres solicitar la cancelación de la orden ${orderId}?`
    );

    if (!confirmed) {
        return;
    }

    const path = `/orders/${encodeURIComponent(orderId)}/cancel`;

    showMessage("Solicitando cancelación a AWS...");

    const { response, data } = await apiRequest(path, {
        method: "POST"
    }, true);

    showResponse("POST", path, response.status, data);
}
async function initializeAuth() {
    try {
        if (!window.auth0) {
            throw new Error("SDK unavailable");
        }

        authClient = new auth0.Auth0Client({
            domain: CONFIG.domain,
            clientId: CONFIG.clientId,
            authorizationParams: {
                audience: CONFIG.audience,
                redirect_uri: CONFIG.redirectUri,
                scope: CONFIG.scope
            },
            cacheLocation: "memory"
        });

        const query = new URLSearchParams(window.location.search);
        const isCallback =
            (query.has("code") && query.has("state")) || query.has("error");

        if (isCallback) {
            try {
                await authClient.handleRedirectCallback();
            } finally {
                // Retira el código de autorización y el estado de la URL.
                window.history.replaceState({}, document.title, "/frontend/index.html");
            }
        } else {
            // Intenta recuperar la sesión con el mecanismo del SDK.
            // Si el navegador lo bloquea, el usuario puede iniciar sesión otra vez.
            try {
                await authClient.checkSession();
            } catch {
                // No se fabrican identidades ni tokens como alternativa.
            }
        }

        await refreshSession();
    } catch {
        element("session-status").textContent =
            "No se pudo completar la inicialización o el retorno de Auth0.";
        showMessage(
            "Comprueba dominio, Client ID, callback y acceso de la aplicación a la API. " +
            "Puedes intentar iniciar sesión de nuevo si el botón está habilitado."
        );
        updateButtons();
    }
}

element("login").addEventListener("click", () => {
    runProtectedAction(async () => {
        const email = element("login-user").value;

        const authorizationParams = {
            audience: CONFIG.audience,
            redirect_uri: CONFIG.redirectUri,
            scope: CONFIG.scope,
            prompt: "login"
        };

        if (email) {
            authorizationParams.login_hint = email;
        }

        await authClient.loginWithRedirect({ authorizationParams });
    });
});

element("logout").addEventListener("click", () => {
    runProtectedAction(async () => {
        await authClient.logout({
            logoutParams: { returnTo: CONFIG.redirectUri }
        });
    });
});

element("refresh-products").addEventListener("click", loadProducts);

element("create-order").addEventListener("click", () => {
    runProtectedAction(createOrder);
});

element("get-order").addEventListener("click", () => {
    runProtectedAction(getOrder);
});
element("cancel-order").addEventListener("click", () => {
    runProtectedAction(cancelOrder);
});
loadProducts();
initializeAuth();