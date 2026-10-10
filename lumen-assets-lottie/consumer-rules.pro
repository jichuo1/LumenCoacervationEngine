# Public typed APIs; no Lumen reflection.
# Lottie 6.7.1 requires Okio 1.17.6, whose optional JSR-305 nullability
# annotation appears only as type metadata. Do not suppress runtime classes.
-dontwarn javax.annotation.Nullable
