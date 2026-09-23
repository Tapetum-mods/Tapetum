#version 120
/* DRAWBUFFERS:0 */

uniform sampler2D texture;
uniform sampler2D lightmap;
varying vec2 atlasUv;
varying vec2 lightUv;
varying vec4 vertexColor;

void main() {
    vec4 surface = texture2D(texture, atlasUv) * vertexColor;
    if (surface.a < 0.1) discard;
    vec3 lighting = texture2D(lightmap, lightUv).rgb;
    gl_FragColor = vec4(surface.rgb * lighting * vec3(0.65, 1.0, 0.8), surface.a);
}
