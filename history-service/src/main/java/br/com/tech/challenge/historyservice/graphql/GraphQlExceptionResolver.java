package br.com.tech.challenge.historyservice.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindException;

/**
 * Traduz excecoes dos resolvers para erros tipados do GraphQL.
 *
 * Sem isto, tudo vira INTERNAL_ERROR com a mensagem substituida por um id opaco, e o cliente nao
 * descobre que enviou um argumento invalido. O que nao e tratado aqui continua mascarado de
 * proposito -- nao devolvemos detalhe de infraestrutura ao cliente.
 */
@Slf4j
@Component
public class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        if (ex instanceof IllegalArgumentException) {
            return badRequest(env, ex.getMessage());
        }

        // O schema tipa os ids como ID!, que aceita qualquer string. Um valor nao numerico passa na
        // validacao do schema e so quebra no bind para Long, virando BindException. Sem este ramo o
        // cliente recebia INTERNAL_ERROR e o servidor gravava um stack trace por query malformada.
        if (ex instanceof BindException) {
            return badRequest(env, "Argumento invalido no campo " + env.getField().getName()
                    + ": os identificadores precisam ser numericos");
        }

        log.error("Erro nao tratado no resolver {}", env.getField().getName(), ex);
        return null;
    }

    private GraphQLError badRequest(DataFetchingEnvironment env, String mensagem) {
        return GraphqlErrorBuilder.newError(env)
                .errorType(ErrorType.BAD_REQUEST)
                .message(mensagem)
                .build();
    }
}
